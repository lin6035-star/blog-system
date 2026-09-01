package com.hailin.blogsystem;

import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.security.AiRateLimitInterceptor;
import com.hailin.blogsystem.security.AiRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 限流拦截器 URI 分类测试。
 *
 * 核心回归点：Workflow 确认类操作（approve/reject/retry/cancel）
 * 不能占用创建入口配额，否则流程内连续确认会被 429 误伤。
 */
class AiRateLimitInterceptorTests {

    private AiRateLimiter rateLimiter;
    private BlogAiProperties properties;
    private HttpServletResponse response;
    private AiRateLimitInterceptor interceptor;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(AiRateLimiter.class);
        when(rateLimiter.tryAcquire(anyString(), anyString(), anyString(), anyInt(), anyLong()))
                .thenReturn(true);

        properties = new BlogAiProperties();
        BlogAiProperties.RateLimit config = properties.getRateLimit();
        config.setEnabled(true);
        config.setWorkflow(new BlogAiProperties.Limit(3, 600));

        response = mock(HttpServletResponse.class);
        interceptor = new AiRateLimitInterceptor(rateLimiter, properties);
    }

    private HttpServletRequest request(String uri, String method) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getMethod()).thenReturn(method);
        // 游客分支会取 IP，不 stub 会得到 null identity 导致 anyString() 不匹配
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        return request;
    }

    @Test
    void workflowConfirmActionsAreNotRateLimited() {
        // 同时覆盖非流式（/approve）和流式（/approve/stream）两种形态
        for (String path : new String[]{
                "/api/ai/workflows/123/approve",
                "/api/ai/workflows/123/approve/stream",
                "/api/ai/workflows/123/reject",
                "/api/ai/workflows/123/reject/stream",
                "/api/ai/workflows/123/retry",
                "/api/ai/workflows/123/retry/stream",
                "/api/ai/workflows/123/cancel"
        }) {
            boolean allowed = interceptor.preHandle(
                    request(path, "POST"),
                    response,
                    null
            );
            assertThat(allowed)
                    .as("workflow %s 不应限流", path)
                    .isTrue();
        }
        verify(rateLimiter, never())
                .tryAcquire(anyString(), anyString(), anyString(), anyInt(), anyLong());
    }

    @Test
    void workflowCreateIsRateLimited() {
        interceptor.preHandle(
                request("/api/ai/workflows/create", "POST"),
                response,
                null
        );

        verify(rateLimiter, org.mockito.Mockito.times(1))
                .tryAcquire(anyString(), anyString(), anyString(), anyInt(), anyLong());
    }

    @Test
    void workflowStatusQueryIsNotRateLimited() {
        interceptor.preHandle(
                request("/api/ai/workflows/123", "GET"),
                response,
                null
        );

        verify(rateLimiter, never())
                .tryAcquire(anyString(), anyString(), anyString(), anyInt(), anyLong());
    }

    @Test
    void chatStreamIsRateLimited() {
        interceptor.preHandle(
                request("/api/ai/chat/stream", "POST"),
                response,
                null
        );

        verify(rateLimiter).tryAcquire(anyString(), anyString(), anyString(), anyInt(), anyLong());
    }

    @Test
    void nonAiEndpointIsNotRateLimited() {
        interceptor.preHandle(
                request("/api/articles/list", "GET"),
                response,
                null
        );

        verify(rateLimiter, never())
                .tryAcquire(anyString(), anyString(), anyString(), anyInt(), anyLong());
    }
}
