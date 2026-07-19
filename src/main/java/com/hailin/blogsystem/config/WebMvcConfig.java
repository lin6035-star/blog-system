package com.hailin.blogsystem.config;

import com.hailin.blogsystem.interceptor.JwtInterceptor;
import com.hailin.blogsystem.interceptor.OptionalJwtInterceptor;
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

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(optionalJwtInterceptor)
                .addPathPatterns(
                        "/api/articles/**",
                        "/api/comments/*/replies"
                );

        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns(
                        "/api/users/me/**",
                        "/api/comments/**",
                        "/api/articles/*/like",
                        "/api/articles/*/favorite",
                        "/api/users/*/follow"
                )
                .excludePathPatterns("/api/comments/*/replies");
    }
}
