package com.hailin.blogsystem;

import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.security.AiRateLimiter;
import com.hailin.blogsystem.security.RateLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiRateLimiterTests {

    @Mock
    private StringRedisTemplate redisTemplate;

    private BlogAiProperties properties;

    private AiRateLimiter limiter;

    @BeforeEach
    void setUp() {
        properties = new BlogAiProperties();
        properties.getRateLimit().setFailOpen(true);
        limiter = new AiRateLimiter(redisTemplate, properties);
    }

    @Test
    void firstRequestInWindowIsAllowed() {
        when(redisTemplate.execute(any(), anyList(), any(String.class))).thenReturn(1L);

        assertThat(limiter.tryAcquire("chat", "user", "101", 10, 60))
                .isTrue();
    }

    @Test
    void requestOverLimitIsRejected() {
        // 窗口内第 4 次请求（limit=3）→ 拒绝
        when(redisTemplate.execute(any(), anyList(), any(String.class))).thenReturn(4L);

        assertThat(limiter.tryAcquire("chat", "user", "101", 3, 600))
                .isFalse();
    }

    @Test
    void newWindowAllowsRequestAgain() {
        // 第二次调用模拟新窗口：INCR 从 1 重新开始（Redis TTL 过期后计数归零）
        when(redisTemplate.execute(any(), anyList(), any(String.class))).thenReturn(1L);

        assertThat(limiter.tryAcquire("chat", "user", "101", 10, 60))
                .isTrue();
    }

    @Test
    void differentUsersHaveIndependentBuckets() {
        when(redisTemplate.execute(any(), anyList(), any(String.class))).thenReturn(1L);

        limiter.tryAcquire("chat", "user", "101", 10, 60);
        limiter.tryAcquire("chat", "user", "102", 10, 60);

        ArgumentCaptor<List<String>> keyCaptor =
                ArgumentCaptor.forClass(List.class);

        verify(redisTemplate, org.mockito.Mockito.times(2))
                .execute(any(), keyCaptor.capture(), any(String.class));

        List<List<String>> allKeys = keyCaptor.getAllValues();
        assertThat(allKeys.get(0).get(0))
                .isNotEqualTo(allKeys.get(1).get(0));
        assertThat(allKeys.get(0).get(0))
                .contains("101");
        assertThat(allKeys.get(1).get(0))
                .contains("102");
    }

    @Test
    void differentBucketsUseDifferentKeys() {
        when(redisTemplate.execute(any(), anyList(), any(String.class))).thenReturn(1L);

        limiter.tryAcquire("chat", "user", "101", 10, 60);
        limiter.tryAcquire("workflow", "user", "101", 3, 600);

        ArgumentCaptor<List<String>> keyCaptor =
                ArgumentCaptor.forClass(List.class);

        verify(redisTemplate, org.mockito.Mockito.times(2))
                .execute(any(), keyCaptor.capture(), any(String.class));

        List<List<String>> allKeys = keyCaptor.getAllValues();
        assertThat(allKeys.get(0).get(0))
                .isNotEqualTo(allKeys.get(1).get(0));
        assertThat(allKeys.get(0).get(0))
                .contains("chat");
        assertThat(allKeys.get(1).get(0))
                .contains("workflow");
    }

    @Test
    void redisFailureFollowsFailOpenConfig() {
        when(redisTemplate.execute(any(), anyList(), any(String.class)))
                .thenThrow(new RuntimeException("redis down"));

        // fail-open=true：Redis 故障时放行（降级，不阻断业务）
        properties.getRateLimit().setFailOpen(true);
        assertThat(limiter.tryAcquire("chat", "user", "101", 10, 60))
                .isTrue();

        // fail-open=false：Redis 故障时拒绝（严格模式，保护 LLM 配额）
        properties.getRateLimit().setFailOpen(false);
        assertThatThrownBy(() ->
                limiter.tryAcquire("chat", "user", "101", 10, 60))
                .isInstanceOf(RateLimitExceededException.class);
    }
}
