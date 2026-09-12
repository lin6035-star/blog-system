package com.hailin.blogsystem.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
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
        // V4⑥ 可观测性：进池时复制 MDC，否则日志 traceId 一进线程池就断
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
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
        // V4⑥ 可观测性：进池时复制 MDC，否则日志 traceId 一进线程池就断
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.initialize();

        return executor;
    }

    /**
     * V4.5：记忆召回专用线程池。
     *
     * 长期记忆与情景记忆的两次召回改成**并行**——原来串行，实测首字前 buildPrompt 占 3.6s，
     * 大头就是这两次向量召回（每次都要算 query embedding + 查 ES），并行后总耗时 ≈ 两者的 max。
     *
     * 独立成池（不复用上面的写侧池）：召回在**请求线程路径**上，被写侧任务挤占会直接拖慢首字。
     * 队列用 CallerRunsPolicy 兜底——池满时退回调用线程执行，宁可慢也不丢。
     */
    @Bean("memoryRecallExecutor")
    public Executor memoryRecallExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("memory-recall-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // V4⑥ 可观测性：进池时复制 MDC，否则日志 traceId 一进线程池就断
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.initialize();

        return executor;
    }
}
