package com.hailin.blogsystem.config;

import com.hailin.blogsystem.interceptor.JwtInterceptor;
import com.hailin.blogsystem.interceptor.OptionalJwtInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册 JwtInterceptor，放行公开接口，只对 /api/users/me/** 做登录校验。
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
                        "/api/comments/**"
                )
                .excludePathPatterns("/api/comments/*/replies");
    }
}
