package com.hailin.blogsystem.component;

import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStringCommands.BitOperation;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 用户日活跃统计（Bitmap 位图）。
 *
 * **为什么用 Bitmap 而不是 Set**：统计只需要回答"这个用户今天在不在"，
 * 不需要知道"是谁"。一个用户占 **1 bit**——1 亿用户的日活是 12.5MB，
 * 换成 Set 存 userId 至少要几个 GB。**Bit 的语义就是"在/不在"**，正好对上这个需求。
 *
 * **能用 userId 当 offset 的前提是自增主键**：项目用 `IdType.AUTO`，ID 连续、从 1 开始，
 * 所以 offset 不会浪费。**换成雪花 ID 就必须先做一层映射**——否则 offset 会冲到 10^18，
 * 一次 SETBIT 就能让 Redis 试图分配天文数字的内存。{@link #MAX_USER_OFFSET} 是这道防线。
 *
 * **写入天然幂等**：同一天重复调用只是把同一位再置一次 1。
 *
 * **单 key 上限**：一天一个 key，offset 上限 1 千万 → 单 key 最大 1.25MB。
 * 这个规模下 BITCOUNT 是微秒级的；真到亿级用户要换成"按用户分片"或 HLL 估算。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserActivityTracker {

    /**
     * offset 上限（用户 ID 上限）。
     *
     * 1 千万 → 单 key 最大 1.25MB。设这道闸不是为了当前规模，是为了**防止 ID 生成方式变更后
     * 静默炸内存**：真用了雪花 ID，一次 SETBIT 就能让 Redis 分配几个 EB。
     */
    private static final long MAX_USER_OFFSET = 10_000_000L;

    /** 置位 + 兜底 TTL 一次完成。理由同浏览量 / UV：「允许丢失的数据都有 TTL」是 volatile-lru 的前提 */
    private static final DefaultRedisScript<Long> MARK_ACTIVE_SCRIPT = new DefaultRedisScript<>(
            """
            redis.call('SETBIT', KEYS[1], ARGV[1], 1)
            if redis.call('TTL', KEYS[1]) < 0 then
                redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            return 1
            """,
            Long.class
    );

    private final StringRedisTemplate stringRedisTemplate;

    /** 记录"当前登录用户今天活跃"。未登录时什么都不做——游客没有 userId 可做 offset */
    public void markActiveToday() {
        Long userId = UserContext.get();
        if (userId != null) {
            markActive(userId, LocalDate.now());
        }
    }

    /** 记录指定用户在某天活跃 */
    public void markActive(Long userId, LocalDate date) {
        if (userId == null || userId <= 0 || userId > MAX_USER_OFFSET) {
            // 越界不记：宁可不统计，也不能让一条脏 ID 把位图撑爆
            return;
        }

        String key = buildKey(date);
        try {
            stringRedisTemplate.execute(
                    MARK_ACTIVE_SCRIPT,
                    Collections.singletonList(key),
                    String.valueOf(userId),
                    String.valueOf(TimeUnit.DAYS.toSeconds(RedisConstants.USER_ACTIVE_TTL_DAYS))
            );
        } catch (Exception e) {
            // 活跃统计失败不影响业务主流程
            log.warn("用户活跃置位失败, userId={} date={}", userId, date, e);
        }
    }

    /** 某天的活跃用户数 */
    public long countActive(LocalDate date) {
        return bitCount(buildKey(date));
    }

    /** 区间内活跃过的用户数：[from, to] 逐天 OR 后计数（周活跃 / 月活跃） */
    public long countActiveBetween(LocalDate from, LocalDate to) {
        List<String> keys = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            keys.add(buildKey(d));
        }
        return bitOpCount(BitOperation.OR, keys);
    }

    /** 两天**都**活跃的用户数（AND 后计数）——留存分析的基础指标 */
    public long countRetained(LocalDate earlier, LocalDate later) {
        return bitOpCount(BitOperation.AND, List.of(buildKey(earlier), buildKey(later)));
    }

    private long bitCount(String key) {
        try {
            Long count = stringRedisTemplate.execute((RedisCallback<Long>) connection ->
                    connection.bitCount(key.getBytes(StandardCharsets.UTF_8)));
            return count == null ? 0 : count;
        } catch (Exception e) {
            log.warn("活跃位图计数失败, key={}", key, e);
            return 0;
        }
    }

    /**
     * BITOP 到临时 key 再计数，用完即删。
     *
     * 临时 key 用 UUID 命名：统计接口可能被并发调用，固定名字会互相覆盖结果。
     * `finally` 里删除——删失败也只是留个带 UUID 的孤儿 key，不会污染正式数据。
     */
    private long bitOpCount(BitOperation op, List<String> sourceKeys) {
        if (sourceKeys.isEmpty()) {
            return 0;
        }

        String destKey = RedisConstants.USER_ACTIVE_KEY_PREFIX + "tmp:" + UUID.randomUUID();
        try {
            byte[][] keys = sourceKeys.stream()
                    .map(k -> k.getBytes(StandardCharsets.UTF_8))
                    .toArray(byte[][]::new);

            stringRedisTemplate.execute((RedisCallback<Long>) connection -> {
                connection.bitOp(op, destKey.getBytes(StandardCharsets.UTF_8), keys);
                return 0L;
            });

            return bitCount(destKey);
        } catch (Exception e) {
            log.warn("活跃位图运算失败, op={} 参与天数={}", op, sourceKeys.size(), e);
            return 0;
        } finally {
            try {
                stringRedisTemplate.delete(destKey);
            } catch (Exception e) {
                log.warn("活跃位图临时 key 删除失败（由 UUID 保证不会污染正式数据）: {}", destKey, e);
            }
        }
    }

    /** 活跃位图 key 按天分：active:user:{yyyyMMdd}（与文章 UV 的日期格式保持一致） */
    private String buildKey(LocalDate date) {
        return RedisConstants.USER_ACTIVE_KEY_PREFIX
                + date.format(DateTimeFormatter.BASIC_ISO_DATE);
    }
}
