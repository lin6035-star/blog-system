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

    /**
     * 会话压缩专用线程池。
     * 与记忆提取分开：stopEvent 里三个异步任务（语义提取 / 情景提取 / 压缩）共用小池会被 LLM 调用阻塞排队，
     * 压缩可能延迟十几秒才启动，前端轮询抓不到"压缩中"状态。独立池保证压缩尽快执行。
     */
    @Bean("conversationSummaryExecutor")
    public Executor conversationSummaryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("summary-compress-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();

        return executor;
    }
}
