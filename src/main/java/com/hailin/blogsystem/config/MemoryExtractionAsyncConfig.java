package com.hailin.blogsystem.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 记忆候选提取专用线程池。
 * 任务极轻（规则判断 + 一次 DB insert），核心线程数小、队列短。
 * 后续接入 LLM 提取时，可按需调大 coreSize 和 queueCapacity。
 */
@Configuration
@RequiredArgsConstructor
public class MemoryExtractionAsyncConfig {

    private final BlogAiProperties blogAiProperties;

    @Bean("memoryCandidateTaskExecutor")
    public Executor memoryCandidateTaskExecutor() {
        BlogAiProperties.Extraction extraction = blogAiProperties.getMemory().getExtraction();

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(extraction.getCoreSize());
        executor.setMaxPoolSize(extraction.getMaxSize());
        executor.setQueueCapacity(extraction.getQueueCapacity());
        executor.setThreadNamePrefix(extraction.getThreadNamePrefix());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();

        return executor;
    }
}
