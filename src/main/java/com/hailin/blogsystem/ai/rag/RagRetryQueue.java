package com.hailin.blogsystem.ai.rag;

import com.hailin.blogsystem.constants.RedisConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * RAG 索引重试队列——用 **ZSet** 实现的延迟队列。
 *
 * **ZSet 当延迟队列的原理**：score 存"下次该执行的时间戳"，取任务就是
 * `ZRANGEBYSCORE 0 now`——**到点的自然排在前面，没到点的取不出来**。
 * 不需要额外的调度器，也不需要给每个任务建一个 key。
 *
 * **取出必须是原子的**（{@link #TAKE_DUE_SCRIPT}）：`ZRANGEBYSCORE` 与 `ZREM` 分开调用时，
 * 定时任务上一轮还没删完、下一轮已经读到同一批——同一个任务被投递两次。
 * 虽然重试本身幂等（索引是覆盖写），但"重复调 LLM / ES"的代价是实打实的。
 *
 * **为什么不直接用 Stream**：Stream 有 ACK、有消费者组、能持久化重投，
 * 能力确实更强；但代价是要维护 pending 列表、处理死信、做 ACK 超时回收。
 * 当前只有"一个应用、一个动作、失败重试"这一种用法，ZSet 的 20 行够用。
 * **触发条件**：需要多消费者分组、需要 at-least-once 保证、或者要跨服务解耦时，再换 Stream 或专业 MQ。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagRetryQueue {

    /**
     * 取出到期任务并删除——**读与删必须在一次 Lua 里完成**。
     *
     * KEYS[1] = 队列，ARGV[1] = 当前时间戳（秒），ARGV[2] = 单次最多取几个
     * 返回：到期的 member 列表（已从 ZSet 移除）
     */
    private static final DefaultRedisScript<List> TAKE_DUE_SCRIPT = new DefaultRedisScript<>(
            """
            local due = redis.call('ZRANGEBYSCORE', KEYS[1], 0, ARGV[1], 'LIMIT', 0, ARGV[2])
            local n = #due
            if n == 0 then
                return {}
            end
            for i = 1, n do
                redis.call('ZREM', KEYS[1], due[i])
            end
            return due
            """,
            List.class
    );

    /** 入队 + 刷新空闲 TTL（一次 Lua，避免 ZADD 成功而 EXPIRE 失败留下永不过期的队列） */
    private static final DefaultRedisScript<Long> ENQUEUE_SCRIPT = new DefaultRedisScript<>(
            """
            redis.call('ZADD', KEYS[1], ARGV[1], ARGV[2])
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            return 1
            """,
            Long.class
    );

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 把一个失败任务排进队列。
     *
     * @param delaySeconds 多久之后可以重试（指数退避由调用方算）。
     *                     传 0 = 立即到期，测试用它避免真的去 sleep
     */
    public void enqueue(String actionKey, Long articleId, int attempt, long delaySeconds) {
        String member = toMember(actionKey, articleId, attempt);
        long dueAt = System.currentTimeMillis() / 1000L + Math.max(0L, delaySeconds);

        try {
            stringRedisTemplate.execute(
                    ENQUEUE_SCRIPT,
                    Collections.singletonList(RedisConstants.RAG_RETRY_QUEUE_KEY),
                    String.valueOf(dueAt),
                    member,
                    String.valueOf(RedisConstants.RAG_RETRY_QUEUE_IDLE_TTL_SECONDS)
            );
            log.info("RAG 重试已排入延迟队列: {} 第 {} 次，{} 秒后到期", member, attempt, delaySeconds);
        } catch (Exception e) {
            // 入队失败只影响"自动重试"这一层：失败标记仍在，人工排查入口不受影响
            log.warn("RAG 重试入队失败（失败标记不受影响，仍可人工处理）: member={}", member, e);
        }
    }

    /**
     * 取出到期的任务，**取走即从队列移除**，保证同一个任务不会被两轮调度同时拿到。
     *
     * @param limit 单轮最多取几个——一轮取太多会把重试挤在同一时刻，反而制造尖峰
     */
    @SuppressWarnings("unchecked")
    public List<Task> takeDue(int limit) {
        try {
            List<String> members = stringRedisTemplate.execute(
                    TAKE_DUE_SCRIPT,
                    Collections.singletonList(RedisConstants.RAG_RETRY_QUEUE_KEY),
                    String.valueOf(System.currentTimeMillis() / 1000L),
                    String.valueOf(limit)
            );

            if (members == null || members.isEmpty()) {
                return List.of();
            }

            return members.stream().map(RagRetryQueue::parse).filter(java.util.Objects::nonNull).toList();
        } catch (Exception e) {
            log.warn("RAG 重试队列取任务失败，本轮跳过（任务仍在队列里，下轮会再取到）", e);
            return List.of();
        }
    }

    /** 队列里当前积压的任务数（用于观测 / 测试断言） */
    public long size() {
        try {
            Long size = stringRedisTemplate.opsForZSet().zCard(RedisConstants.RAG_RETRY_QUEUE_KEY);
            return size == null ? 0 : size;
        } catch (Exception e) {
            return 0;
        }
    }

    /** member 里带 attempt：ZSet 的 score 只能存时间，重试次数得自己带着走 */
    private static String toMember(String actionKey, Long articleId, int attempt) {
        return actionKey + ":" + (articleId == null ? "all" : articleId) + ":" + attempt;
    }

    /** 解析失败返回 null（成员被外部改坏时跳过，不让一条脏数据打断整轮消费） */
    private static Task parse(String member) {
        String[] parts = member.split(":");
        if (parts.length != 3) {
            log.warn("RAG 重试队列成员格式非法，已跳过: {}", member);
            return null;
        }
        try {
            return new Task(parts[0], "all".equals(parts[1]) ? null : Long.valueOf(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            log.warn("RAG 重试队列成员数字非法，已跳过: {}", member);
            return null;
        }
    }

    /**
     * @param actionKey index / delete / rebuild
     * @param articleId 全量重建时为 null
     * @param attempt   这是第几次队列重试（从 1 开始）
     */
    public record Task(String actionKey, Long articleId, int attempt) {
    }
}
