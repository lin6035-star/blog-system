package com.hailin.blogsystem.security;

import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.constants.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI 接口固定时间窗口限流器。
 *
 * Lua 脚本一次完成 INCR + 首次设置 TTL + 返回当前计数，
 * 避免 INCR / EXPIRE 分开调用在并发下计数与 TTL 不一致。
 *
 * Key 结构：ai:rate:{bucket}:{user|ip}:{identity}:{window}
 * bucket = chat / workflow / rag，identity = userId 或 IP。
 *
 * Redis 异常时按 fail-open 配置降级：
 * - failOpen=true：放行（不阻断业务，配额保护失效）
 * - failOpen=false：拒绝（严格模式，抛 RateLimitExceededException）
 */
@Slf4j
@Component
public class AiRateLimiter {

    private static final DefaultRedisScript<Long> INCR_AND_EXPIRE_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    local current = redis.call('INCR', KEYS[1])
                    if current == 1 then
                        redis.call('EXPIRE', KEYS[1], ARGV[1])
                    end
                    return current
                    """,
                    Long.class
            );

    private final StringRedisTemplate redisTemplate;
    private final BlogAiProperties properties;

    public AiRateLimiter(
            StringRedisTemplate redisTemplate,
            BlogAiProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * 尝试获取一次配额。
     *
     * @param bucket       限流桶：chat / workflow / rag
     * @param type         身份类型：user / ip
     * @param identity     userId 或 IP
     * @param limit        窗口内限额
     * @param windowSeconds 窗口秒数
     * @return true=放行，false=超限
     */
    public boolean tryAcquire(
            String bucket,
            String type,
            String identity,
            int limit,
            long windowSeconds
    ) {
        String key = RedisConstants.AI_RATE_LIMIT_KEY_PREFIX
                + bucket + ":"
                + type + ":"
                + identity + ":"
                + windowSeconds;

        try {
            Long count = redisTemplate.execute(
                    INCR_AND_EXPIRE_SCRIPT,
                    List.of(key),
                    String.valueOf(windowSeconds)
            );

            long current = count == null ? 0 : count;
            boolean allowed = current <= limit;

            if (!allowed) {
                log.info(
                        "[RATE-LIMIT] rejected bucket={} type={} identity={} count={}/{} window={}s",
                        bucket, type, identity, current, limit, windowSeconds
                );
            }

            return allowed;
        } catch (RuntimeException e) {
            if (properties.getRateLimit().isFailOpen()) {
                log.warn(
                        "[RATE-LIMIT] Redis 故障，fail-open 放行: bucket={}",
                        bucket,
                        e
                );
                return true;
            }

            log.error(
                    "[RATE-LIMIT] Redis 故障，fail-open=false 拒绝: bucket={}",
                    bucket,
                    e
            );
            throw new RateLimitExceededException(windowSeconds);
        }
    }
}
