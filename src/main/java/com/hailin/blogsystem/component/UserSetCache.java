package com.hailin.blogsystem.component;

import com.hailin.blogsystem.constants.RedisConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 用户维度集合缓存（点赞 / 收藏）。
 *
 * **它解决什么**：`{key}` 存用户点赞/收藏的 ID 集合，`{key}:loaded` 标记"这个集合是否已从 DB 全量加载"。
 * 原来点赞路径**无条件**写标记——Redis 重启后用户点一个赞，集合里只有这一个元素却自称全量，
 * 展示出来就是"历史点赞全丢了"（数据展示错误，且每次操作刷新标记 TTL → 错误状态无限延长）。
 *
 * **状态语义**（`{key}:loaded` 的值）：
 * - `FULL` —— 集合非空，且是 DB 全量快照，可信任
 * - `EMPTY` —— 用户确实没有数据（不是"没查到"，是"查过了，就是空"）
 * - 旧值 `1` —— 迁移期兼容，按 FULL 处理，但集合缺失时不信任（见下）
 * - 不存在 —— 未加载，调用方必须回源 DB
 *
 * **三条不变量**：
 * 1. 只有从 DB 全量加载过的分支才能写标记；点赞/取消**只在标记已存在时**维护集合
 * 2. `FULL` 但集合缺失 = 异常（部分淘汰 / 被误删）→ 按未加载处理回源重建，**不能当空集合返回**
 * 3. Set TTL(40 min) > 标记 TTL(30 min)，且**先写 Set、后写标记**——反过来会出现
 *    "标记还在、Set 已空"的窗口，读到空集合 = 静默显示"没点过赞"
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserSetCache {

    /** 标记"FULL"用：集合非空且可信。取值必须与 {@link #MAINTAIN_SCRIPT} 里的字面量一致 */
    private static final String STATE_FULL = "FULL";
    /** 标记"EMPTY"用：查过了，用户确实没有数据。取值必须与脚本里的字面量一致 */
    private static final String STATE_EMPTY = "EMPTY";

    /**
     * 已加载状态下维护集合（检查标记 + 集合变更 + 刷新两个 TTL）。
     *
     * 这几步必须在同一脚本里完成：拆成多次调用时，检查与写入之间标记可能过期，
     * 于是 SADD 会重新创建一个只有新元素的集合——又变回"集合自称全量但只有一条"的老问题。
     *
     * KEYS[1] = 集合 key，KEYS[2] = 标记 key
     * ARGV[1] = 元素，ARGV[2] = Set TTL(秒)，ARGV[3] = 标记 TTL(秒)，ARGV[4] = ADD / REMOVE
     */
    private static final DefaultRedisScript<Long> MAINTAIN_SCRIPT = new DefaultRedisScript<>(
            """
            local state = redis.call('GET', KEYS[2])
            -- 迁移期旧值：集合在才敢当 FULL 用，集合没了说明历史数据不可信，宁可不维护
            if state == '1' and redis.call('EXISTS', KEYS[1]) == 0 then
                return 0
            end
            if not state then
                return 0
            end

            if ARGV[4] == 'ADD' then
                redis.call('SADD', KEYS[1], ARGV[1])
                redis.call('EXPIRE', KEYS[1], ARGV[2])
                redis.call('SET', KEYS[2], 'FULL', 'EX', ARGV[3])
                return 1
            end

            redis.call('SREM', KEYS[1], ARGV[1])
            if redis.call('SCARD', KEYS[1]) == 0 then
                -- 取消最后一项：集合置空，状态转 EMPTY。
                -- 不能留下「FULL + 缺集合」——那会被读路径判成异常，白白多一次回源
                redis.call('DEL', KEYS[1])
                redis.call('SET', KEYS[2], 'EMPTY', 'EX', ARGV[3])
            else
                redis.call('EXPIRE', KEYS[1], ARGV[2])
                redis.call('SET', KEYS[2], 'FULL', 'EX', ARGV[3])
            end
            return 1
            """, Long.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final AfterCommitExecutor afterCommitExecutor;

    /**
     * 读取已加载的集合。
     *
     * @return {@code null} = 未加载或状态不可信，**调用方必须回源 DB 并调 {@link #markLoaded}**；
     *         空 Set = 已确认该用户没有数据；非空 Set = 命中
     */
    public Set<String> readIfLoaded(String setKey) {
        String state;
        try {
            state = stringRedisTemplate.opsForValue().get(loadedKey(setKey));
        } catch (Exception e) {
            return null;   // Redis 故障：当未加载处理，走 DB 兜底
        }

        if (state == null) {
            return null;
        }
        if (STATE_EMPTY.equals(state)) {
            return Set.of();
        }

        // FULL，或迁移期旧值 "1"
        Set<String> members;
        try {
            members = stringRedisTemplate.opsForSet().members(setKey);
        } catch (Exception e) {
            return null;
        }

        // 标记说全量、集合却不在（内存淘汰 / 误删）→ 状态不可信，回源重建。
        // 这里不能返回空集合：那等于告诉用户"你没点过赞"
        return members == null || members.isEmpty() ? null : members;
    }

    /**
     * 从 DB 全量加载后回填缓存。
     *
     * @param members DB 查出的全量数据；空集合写入 {@code EMPTY} 而不是建一个空 Set
     */
    public void markLoaded(String setKey, Set<String> members) {
        try {
            if (members.isEmpty()) {
                // 只标 EMPTY。同时清掉可能残留的旧集合（Set TTL 比标记长，标记过期时集合可能还在）
                stringRedisTemplate.delete(setKey);
                stringRedisTemplate.opsForValue()
                        .set(loadedKey(setKey), STATE_EMPTY,
                                RedisConstants.USER_SET_LOADED_TTL_MINUTES, TimeUnit.MINUTES);
                return;
            }

            // 先清空再写：SADD 是追加不是覆盖，不清会把残留成员留下来。
            // 触发链——标记过期(30min)但集合还在(40min)，这期间用户取消了点赞
            // （未加载状态下维护逻辑正确地什么都没做，DB 已删），随后回源重建时
            // 残留成员被保留 → 已取消的点赞"复活"。
            // delete 与 add 之间的窗口是安全的：读路径看到集合为空会判为"状态不可信"去回源
            stringRedisTemplate.delete(setKey);
            // 先写 Set（带 TTL），后写标记 —— 顺序反了会留下「标记在、Set 空」的窗口
            stringRedisTemplate.opsForSet().add(setKey, members.toArray(new String[0]));
            stringRedisTemplate.expire(setKey,
                    RedisConstants.USER_SET_TTL_MINUTES, TimeUnit.MINUTES);
            stringRedisTemplate.opsForValue()
                    .set(loadedKey(setKey), STATE_FULL,
                            RedisConstants.USER_SET_LOADED_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            // 回填失败不影响本次业务结果（本次用的是 DB 查出的数据），下次读会重试
            log.warn("用户集合缓存回填失败，setKey={}", setKey, e);
        }
    }

    /** 点赞 / 收藏：仅在集合已加载时维护，未加载时什么都不做（保持"未加载"让下次读全量重建） */
    public void addIfLoaded(String setKey, String element) {
        maintain(setKey, element, "ADD");
    }

    /** 取消点赞 / 取消收藏：仅在集合已加载时维护；取消最后一项时状态转为 EMPTY */
    public void removeIfLoaded(String setKey, String element) {
        maintain(setKey, element, "REMOVE");
    }

    /**
     * 集合维护走 {@link AfterCommitExecutor}：调用方（点赞 / 取消点赞）在事务里，
     * 提交前动集合会出现"事务回滚了、集合却已经改了"的错位——
     * 最坏是取消最后一项把状态写成 EMPTY，而那条取消根本没生效。
     * 读路径的 {@link #markLoaded} 不走它：回填必须立即生效给后续请求用，且读路径本就没有事务。
     */
    private void maintain(String setKey, String element, String action) {
        afterCommitExecutor.execute(() -> {
            try {
                stringRedisTemplate.execute(MAINTAIN_SCRIPT,
                        List.of(setKey, loadedKey(setKey)),
                        element,
                        String.valueOf(TimeUnit.MINUTES.toSeconds(RedisConstants.USER_SET_TTL_MINUTES)),
                        String.valueOf(TimeUnit.MINUTES.toSeconds(RedisConstants.USER_SET_LOADED_TTL_MINUTES)),
                        action);
            } catch (Exception e) {
                // 集合维护失败不影响业务结果：点赞本身已落库，下次读集合时从 DB 重建
                log.warn("用户集合缓存维护失败，setKey={} element={} action={}", setKey, element, action, e);
            }
        });
    }

    private String loadedKey(String setKey) {
        return setKey + RedisConstants.USER_SET_LOADED_SUFFIX;
    }
}
