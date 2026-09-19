package com.hailin.blogsystem.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * AI 长任务专用线程池。
 *
 * <p>此前 Agent / Workflow 编排任务直接提交到 Reactor 的<b>全局共享</b> {@code boundedElastic}
 * （上限约 CPU 核数 × 10，实测 20 核 = 200 线程，队列 10 万），与全应用其他异步任务挤在一起，
 * 且没有用户级上限。本池把它们独立出来。
 *
 * <p>三条决策（都写在设计稿 {@code docs/agent/agent-long-task-admission-design.md} §3.3）：
 * <ul>
 *   <li><b>core == max</b>：{@code ThreadPoolExecutor} 的规则是先开到 core、随后先入队，
 *       只有队列满才扩到 max。设成 core=8/max=16/queue=32 会让第 9～40 个任务先排队，
 *       明明允许 16 个 worker 却迟迟不用，{@code queueWaitMs} 被配置本身人为放大。
 *       固定 worker 数的容量模型最简单：同时跑 {@code workers} 个，额外排队 {@code queue} 个。</li>
 *   <li><b>{@code AbortPolicy}，不是 {@code CallerRunsPolicy}</b>：后者池满时退回调用者线程执行，
 *       对短任务是有意降级，对几十秒到几分钟的长任务等于把调用者线程占死——
 *       而调用者是 SSE 的订阅线程，隔离直接失效。</li>
 *   <li><b>不设 {@code TaskDecorator}</b>：MDC 与 traceId 由
 *       {@code AiOrchestrationTaskAdmission} 用 {@code MdcContext.wrap} 显式恢复，
 *       再叠加 {@code ContextPropagatingTaskDecorator} 会造成两套上下文传播互相覆盖。</li>
 * </ul>
 */
@Configuration
@RequiredArgsConstructor
public class AiTaskAsyncConfig {

    private final BlogAiProperties blogAiProperties;

    @Bean("aiTaskExecutor")
    public ThreadPoolTaskExecutor aiTaskExecutor() {
        BlogAiProperties.Task task = blogAiProperties.getTask();

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(task.getWorkerCount());
        executor.setMaxPoolSize(task.getWorkerCount());
        executor.setQueueCapacity(task.getQueueCapacity());
        executor.setThreadNamePrefix(task.getThreadNamePrefix());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();

        return executor;
    }
}
