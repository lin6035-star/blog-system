package com.hailin.blogsystem;

import com.hailin.blogsystem.security.TokenBlacklist;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenBlacklistTests {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private TokenBlacklist blacklist;

    @BeforeEach
    void setUp() {
        blacklist = new TokenBlacklist(redisTemplate);
    }

    @Test
    void revokeWritesKeyWithTokenRemainingLifetime() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        blacklist.revoke("jti-1", Duration.ofSeconds(600));

        // TTL 是 token 的剩余有效期而不是固定时长：条目只需活到 token 自己过期
        verify(valueOperations).set("token:blacklist:jti-1", "1", Duration.ofSeconds(600));
    }

    @Test
    void revokeWithoutJtiIsSkipped() {
        // 加 jti 之前签发的老 token：没有键可拉黑，直接跳过（不产生 "token:blacklist:null"）
        blacklist.revoke(null, Duration.ofSeconds(600));

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void revokeWithExpiredTokenIsSkipped() {
        // 已过期的 token 本来就无效，写黑名单纯属浪费内存
        blacklist.revoke("jti-1", Duration.ofSeconds(-5));

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void isRevokedReflectsKeyExistence() {
        when(redisTemplate.hasKey("token:blacklist:jti-1")).thenReturn(true);
        when(redisTemplate.hasKey("token:blacklist:jti-2")).thenReturn(false);

        assertThat(blacklist.isRevoked("jti-1")).isTrue();
        assertThat(blacklist.isRevoked("jti-2")).isFalse();
    }

    @Test
    void isRevokedWithoutJtiIsFalse() {
        assertThat(blacklist.isRevoked(null)).isFalse();

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void redisFailureFailsOpenOnRead() {
        when(redisTemplate.hasKey("token:blacklist:jti-1"))
                .thenThrow(new RuntimeException("redis down"));

        // Redis 故障时按未拉黑放行：登出令牌多活一会儿，好过全站 401
        assertThat(blacklist.isRevoked("jti-1")).isFalse();
    }

    @Test
    void redisFailureOnRevokeDoesNotPropagate() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(valueOperations).set("token:blacklist:jti-1", "1", Duration.ofSeconds(600));

        // 拉黑失败不能把"登出"这个动作变成错误响应：本地状态该清还是要清
        assertThatCode(() -> blacklist.revoke("jti-1", Duration.ofSeconds(600)))
                .doesNotThrowAnyException();
    }
}
