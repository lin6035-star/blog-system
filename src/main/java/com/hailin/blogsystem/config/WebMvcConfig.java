package com.hailin.blogsystem.config;

import com.hailin.blogsystem.interceptor.JwtInterceptor;
import com.hailin.blogsystem.interceptor.OptionalJwtInterceptor;
import com.hailin.blogsystem.security.AiRateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 公开浏览接口允许游客访问，互动和用户接口需要登录。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;
    private final OptionalJwtInterceptor optionalJwtInterceptor;
    private final AiRateLimitInterceptor aiRateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(optionalJwtInterceptor)
                .addPathPatterns(
                        "/api/articles/**",
                        "/api/comments/*/replies",
                        "/api/users/*",
                        "/api/users/*/articles",
                        "/api/users/*/liked",
                        "/api/users/*/favorited",
                        "/api/users/*/commented",
                        "/api/users/*/followers",
                        "/api/users/*/following",
                        // "/api/ai/chat",  // 【已废弃】非流式接口
                        "/api/ai/chat/stream",
                        // 秒杀活动列表：游客能看还剩多少，登录用户额外带上自己的参与状态
                        "/api/seckill/activities"
                )
                .excludePathPatterns(
                        "/api/users/me",
                        "/api/users/me/**"
                );

        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns(
                        "/api/users/me",
                        "/api/users/me/**",
                        "/api/comments/**",
                        "/api/articles/*/like",
                        "/api/articles/*/favorite",
                        "/api/users/*/follow",
                        "/api/ai/**",
                        "/api/learning-plans/**",
                        "/api/wallet/**",
                        "/api/seckill/**"
                )
                .excludePathPatterns("/api/comments/*/replies",
                                     // "/api/ai/chat",  // 【已废弃】非流式接口
                                     "/api/ai/chat/stream",
                                     // 活动列表游客可见（抢购本身要登录）。Ant 模式按路径精确匹配，
                                     // 不会连带放行 /activities/{id}/grab 和 /admin/**
                                     "/api/seckill/activities");

        // AI 限流：只拦会消耗 LLM / ES 的请求（chat stream / workflow 创建与推进 / rag search）
        // 必须排在 jwt 拦截器之后，才能拿到 UserContext 的 userId
        registry.addInterceptor(aiRateLimitInterceptor)
                .addPathPatterns(
                        "/api/ai/chat/stream",
                        "/api/ai/workflows/**",
                        "/api/ai/rag/articles/search",
                        // 秒杀抢购。结果轮询**不**挂在这里：它是前端每秒一次的正常行为，
                        // 限流它等于让用户还没抢到就先撞 429
                        "/api/seckill/activities/*/grab"
                );
    }
}
