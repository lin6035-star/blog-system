package com.hailin.blogsystem.security;

import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.utils.ClientIpUtils;
import com.hailin.blogsystem.utils.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * AI 接口限流拦截器：只拦会消耗 LLM / ES 的请求。
 *
 * - 登录用户按 userId 限流，游客按 IP 限流（游客使用更低限额）
 * - 在 Controller 之前拦截（SSE 场景在进入业务流前就拒绝，不会等 LLM 开始生成）
 * - 超限抛 RateLimitExceededException，由 GlobalExceptionHandler 转 HTTP 429
 *
 * 不限制：查询历史消息 / Workflow 状态 / 取消 / 普通文章浏览 / 点赞收藏评论。
 */
@Component
public class AiRateLimitInterceptor implements HandlerInterceptor {

    private static final String BUCKET_CHAT = "chat";
    private static final String BUCKET_WORKFLOW = "workflow";
    private static final String BUCKET_RAG = "rag";

    private final AiRateLimiter rateLimiter;
    private final BlogAiProperties properties;

    public AiRateLimitInterceptor(
            AiRateLimiter rateLimiter,
            BlogAiProperties properties
    ) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        BlogAiProperties.RateLimit config = properties.getRateLimit();
        if (config == null || !config.isEnabled()) {
            return true;
        }

        String bucket = resolveBucket(request);
        if (bucket == null) {
            return true;
        }

        BlogAiProperties.Limit limit = switch (bucket) {
            case BUCKET_CHAT -> config.getChat();
            case BUCKET_WORKFLOW -> config.getWorkflow();
            case BUCKET_RAG -> config.getRag();
            default -> null;
        };

        if (limit == null || limit.getLimit() <= 0) {
            return true;
        }

        Long userId = UserContext.get();
        String type;
        String identity;

        if (userId != null) {
            type = "user";
            identity = String.valueOf(userId);
        } else {
            // 游客：按 IP 限流，使用登录用户的一半限额（下限 1）
            type = "ip";
            identity = ClientIpUtils.getClientIp(request);
            int guestLimit = Math.max(1, limit.getLimit() / 2);
            limit = new BlogAiProperties.Limit(guestLimit, limit.getWindowSeconds());
        }

        boolean allowed = rateLimiter.tryAcquire(
                bucket,
                type,
                identity,
                limit.getLimit(),
                limit.getWindowSeconds()
        );

        if (!allowed) {
            throw new RateLimitExceededException(limit.getWindowSeconds());
        }

        return true;
    }

    /**
     * 按 URI 分类限流桶；不消耗 LLM / ES 的请求返回 null（不限流）。
     */
    private String resolveBucket(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String method = request.getMethod();

        if ("/api/ai/chat/stream".equals(uri)) {
            return BUCKET_CHAT;
        }

        if ("/api/ai/rag/articles/search".equals(uri)) {
            return BUCKET_RAG;
        }

        // Workflow：只拦"创建"（POST），GET 状态查询不拦。
        // 确认类操作（approve/reject/retry/cancel）是流程内正常交互，
        // 次数天然受 Workflow 步骤数限制，不占用创建入口配额，否则
        // 一个流程连续确认几次就被 429 误伤。
        // 注意：流式确认端点是 /{id}/approve/stream，用包含判断覆盖两种形态。
        if (uri.startsWith("/api/ai/workflows/")) {
            if ("GET".equalsIgnoreCase(method)) {
                return null;
            }
            if (uri.contains("/cancel")
                    || uri.contains("/approve")
                    || uri.contains("/reject")
                    || uri.contains("/retry")) {
                return null;
            }
            return BUCKET_WORKFLOW;
        }

        return null;
    }
}
