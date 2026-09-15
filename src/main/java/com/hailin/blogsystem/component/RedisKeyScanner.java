package com.hailin.blogsystem.component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Redis 按 pattern 扫描 / 批量删除。
 *
 * **解决什么**：`KEYS pattern` 是 O(N) 且**阻塞 Redis 服务端单线程**——Redis 里同时住着
 * 限流、分布式锁、业务缓存，一次 KEYS 会让它们全部一起卡。改用 SCAN 游标迭代，每次只扫一批。
 *
 * **SCAN 的代价**（调用方必须知道）：
 * - 不保证快照一致性：扫描期间新增的 key 可能扫不到（缓存场景由 TTL 兜底）
 * - 可能重复返回同一个 key（调用方需保证幂等）
 *
 * **删除用 UNLINK 而非 DEL**：DEL 是同步删除，key 多时会阻塞；UNLINK 把内存回收交给后台线程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisKeyScanner {

    /** 每批扫描 / 删除的数量 */
    private static final int BATCH_SIZE = 200;

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 扫描匹配的 key 并批量删除（边扫边删，不把全部 key 堆在内存里）。
     *
     * @return 删除次数（SCAN 可能重复返回同一 key，故该值不保证等于唯一 key 数）
     */
    public long scanAndDelete(String pattern) {
        long deleted = 0;
        try (Cursor<String> cursor = stringRedisTemplate.scan(buildOptions(pattern))) {
            List<String> batch = new ArrayList<>(BATCH_SIZE);
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() >= BATCH_SIZE) {
                    deleted += unlink(batch);
                    batch.clear();
                }
            }
            deleted += unlink(batch);
        } catch (Exception e) {
            log.warn("SCAN 删除失败，pattern={}", pattern, e);
        }
        return deleted;
    }

    /**
     * 扫描并返回匹配的 key 列表（调用方需要先拿到全部 key 再处理的场景）。
     */
    public List<String> scan(String pattern) {
        List<String> keys = new ArrayList<>();
        try (Cursor<String> cursor = stringRedisTemplate.scan(buildOptions(pattern))) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        } catch (Exception e) {
            log.warn("SCAN 查询失败，pattern={}", pattern, e);
        }
        return keys;
    }

    private long unlink(List<String> keys) {
        if (keys.isEmpty()) {
            return 0;
        }
        try {
            Long removed = stringRedisTemplate.unlink(keys);
            return removed == null ? 0 : removed;
        } catch (Exception e) {
            log.warn("UNLINK 失败，本批 {} 个 key", keys.size(), e);
            return 0;
        }
    }

    private ScanOptions buildOptions(String pattern) {
        return ScanOptions.scanOptions()
                .match(pattern)
                .count(BATCH_SIZE)
                .build();
    }
}
