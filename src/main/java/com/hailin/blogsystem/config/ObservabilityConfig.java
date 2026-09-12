package com.hailin.blogsystem.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/**
 * V4⑥ 可观测性：把 MDC 传播到 Reactor 调度线程。
 *
 * <p>背景：Agent 循环跑在 {@code Schedulers.boundedElastic()} 上（见
 * {@code AiMessageServiceImpl.streamAgentReply}），Reactor 的调度不会继承 ThreadLocal——
 * 实测 Agent 全程日志 traceId 为空（分类器有、Agent Run 创建之后全丢）。
 * 项目里对 {@code UserContext} 也是同样原因手动恢复（{@code UserContext.set(userId)}），
 * 本配置把 MDC 这一份统一解决。
 *
 * <p>用 Reactor 官方的 {@code onScheduleHook}：一次注册覆盖所有调度器（含将来新增的调度点），
 * 避免逐个 {@code schedule} 点手写包裹、新增时漏掉。
 *
 * <p>钩子的装饰器在**任务被调度时**执行（此时仍在调用方线程），因此能捕获到提交方的 MDC；
 * {@code finally} 里的 {@code MDC.clear()} 用于清理池化线程，防止上下文串到下一个任务。
 */
@Configuration
public class ObservabilityConfig {

    private static final String HOOK_KEY = "mdc-context-propagation";

    @PostConstruct
    public void propagateMdcToReactorSchedulers() {
        Schedulers.onScheduleHook(HOOK_KEY, runnable -> {
            Map<String, String> contextMap = MDC.getCopyOfContextMap();
            return () -> {
                if (contextMap != null) {
                    MDC.setContextMap(contextMap);
                }
                try {
                    runnable.run();
                } finally {
                    MDC.clear();
                }
            };
        });
    }
}
