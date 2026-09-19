package com.hailin.blogsystem.security;

import com.hailin.blogsystem.constants.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 登出 token 黑名单。
 *
 * JWT 是无状态的：签发之后，服务端没有任何办法提前作废它——黑名单是唯一的手段。
 * 代价是每个请求多一次 Redis `GET`（内存操作，亚毫秒级），换来"登出即时生效"。
 *
 * 键 = `token:blacklist:{jti}`，**TTL 取 token 的剩余有效期**而不是固定时长：
 * 条目只需要活到 token 自己过期为止，多存一秒都是浪费内存。
 *
 * **Redis 故障时按"未拉黑"放行**（fail-open），只留 ERROR 日志：
 * 代价是 Redis 恢复前已登出的 token 仍可用，但远小于"Redis 一抖全站 401"。
 * JWT 自身的 7 天过期是兜底防线，黑名单是加强项、不是唯一防线。
 */
@Slf4j
@Component
public class TokenBlacklist {

    private final StringRedisTemplate redisTemplate;

    public TokenBlacklist(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 拉黑一个 token。
     *
     * @param jti token 唯一标识。为 null（加 jti 之前签发的老 token）时直接跳过
     * @param ttl 存活时长，取 token 的剩余有效期；≤ 0（token 已过期）时无需拉黑
     */
    public void revoke(String jti, Duration ttl) {
        if (jti == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }

        try {
            redisTemplate.opsForValue().set(key(jti), "1", ttl);
        } catch (RuntimeException e) {
            log.error("[TOKEN-BLACKLIST] 拉黑写入失败，该 token 在自然过期前仍可用: jti={}", jti, e);
        }
    }

    /** 是否已被拉黑；Redis 故障时返回 false（放行），理由见类注释 */
    public boolean isRevoked(String jti) {
        if (jti == null) {
            return false;
        }

        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key(jti)));
        } catch (RuntimeException e) {
            log.error("[TOKEN-BLACKLIST] 查询失败，按未拉黑放行: jti={}", jti, e);
            return false;
        }
    }

    private String key(String jti) {
        return RedisConstants.TOKEN_BLACKLIST_KEY_PREFIX + jti;
    }
}
