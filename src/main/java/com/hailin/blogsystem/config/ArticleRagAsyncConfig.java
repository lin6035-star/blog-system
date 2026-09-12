package com.hailin.blogsystem.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@RequiredArgsConstructor
public class ArticleRagAsyncConfig {

    private final BlogAiProperties blogAiProperties;

    @Bean("articleRagTaskExecutor")
    public Executor articleRagTaskExecutor() {
        BlogAiProperties.Async async = blogAiProperties.getRag().getAsync();

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(async.getCoreSize());
        executor.setMaxPoolSize(async.getMaxSize());
        executor.setQueueCapacity(async.getQueueCapacity());
        executor.setThreadNamePrefix(async.getThreadNamePrefix());

        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // V4⑥ 可观测性：进池时复制 MDC，否则日志 traceId 一进线程池就断
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.initialize();

        return executor;
    }
}