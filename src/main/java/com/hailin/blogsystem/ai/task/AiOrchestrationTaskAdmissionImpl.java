package com.hailin.blogsystem.ai.task;

import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.exception.AiTaskRejectedException;
import com.hailin.blogsystem.exception.AiTaskRejectedException.Reason;
import com.hailin.blogsystem.utils.MdcContext;
import com.hailin.blogsystem.utils.UserContext;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 准入实现：每用户内存计数 + 专用有界池 + 统一观测。
 *
 * <p><b>为什么不另设全局 Semaphore</b>（设计稿 §3.3 论证过，这里落地）：
 * 固定 {@code core == max} + 有界队列 + {@code AbortPolicy} 之后，线程池本身就是全局容量真相，
 * {@code execute()} 在满员时会<b>同步抛</b> {@link RejectedExecutionException}。再加一层 Semaphore
 * 等于同时存在两套容量状态，还要处理 permit 的领取/回滚与两边竞态。
 *
 * <p><b>为什么用内存计数而不是 Redis</b>（§4.1）：项目当前单实例，内存计数跟着进程走、
 * 进程挂了计数自然消失，没有残留脏计数要处理；跨实例的升级路径写在设计稿 §7。
 */
@Component
@Slf4j
public class AiOrchestrationTaskAdmissionImpl implements AiOrchestrationTaskAdmission {

    private final ThreadPoolTaskExecutor aiTaskExecutor;
    private final BlogAiProperties blogAiProperties;
    private final ObjectProvider<Tracer> tracerProvider;

    /**
     * 每用户 in-flight 计数 = 正在运行 + 已进入队列。
     *
     * <p>用 {@code compute} 而不是「{@code AtomicInteger.decrementAndGet() == 0} 后 remove」：
     * 后者在释放线程准备 remove 时，另一个提交线程可能刚把同一个计数器从 0 加回 1，
     * 随后旧线程仍把它 remove，导致活跃任务从计数表消失。
     * {@code compute} 按 key 串行化更新，计数归零时直接返回 null，原子性与 map 清理一并保证。
     */
    private final ConcurrentHashMap<Long, Integer> inFlightByUser = new ConcurrentHashMap<>();

    public AiOrchestrationTaskAdmissionImpl(
            @Qualifier("aiTaskExecutor") ThreadPoolTaskExecutor aiTaskExecutor,
            BlogAiProperties blogAiProperties,
            ObjectProvider<Tracer> tracerProvider
    ) {
        this.aiTaskExecutor = aiTaskExecutor;
        this.blogAiProperties = blogAiProperties;
        this.tracerProvider = tracerProvider;
    }

    @Override
    public void precheck(Long userId, AiTaskType taskType, String businessRef) {
        if (userId != null && currentOf(userId) >= maxPerUser()) {
            log.info("[AI-TASK] precheck rejected reason=USER_LIMIT taskType={} businessRef={} userId={} inFlightOfUser={}",
                    taskType, businessRef, userId, currentOf(userId));
            throw new AiTaskRejectedException(Reason.USER_LIMIT);
        }
        if (isPoolFull()) {
            log.info("[AI-TASK] precheck rejected reason=POOL_FULL taskType={} businessRef={} userId={} running={} queued={}",
                    taskType, businessRef, userId, activeCount(), queuedCount());
            throw new AiTaskRejectedException(Reason.POOL_FULL);
        }
    }

    @Override
    public void submit(AiTaskRequest request, Runnable task) {
        Long userId = request.userId();

        // ① 权威判定：原子占用用户名额。竞态结果是「这个任务被拒」，不是「多跑一个」。
        if (userId != null && !acquireUserSlot(userId)) {
            log.info("[AI-TASK] submit rejected reason=USER_LIMIT taskType={} businessRef={} userId={} inFlightOfUser={}",
                    request.taskType(), request.businessRef(), userId, currentOf(userId));
            throw new AiTaskRejectedException(Reason.USER_LIMIT);
        }

        // ② 提交线程池。被拒时 task 根本不会进入 worker，finally 不会执行 —— 必须在这里回滚，
        //    否则该用户永久少一个名额（直到重启）。
        try {
            aiTaskExecutor.execute(wrap(request, task));
        } catch (RejectedExecutionException e) {
            releaseUserSlot(userId);
            boolean shutdown = aiTaskExecutor.getThreadPoolExecutor().isShutdown();
            log.warn("[AI-TASK] submit rejected reason=POOL_FULL taskType={} businessRef={} userId={} executorShutdown={} running={} queued={}",
                    request.taskType(), request.businessRef(), userId, shutdown, activeCount(), queuedCount());
            throw new AiTaskRejectedException(Reason.POOL_FULL);
        }
    }

    @Override
    public <T> T runAdmitted(AiTaskRequest request, Supplier<T> task) {
        Long userId = request.userId();

        // 权威判定同上：只有真正占住名额，每用户上限才对这条路径成立。
        if (userId != null && !acquireUserSlot(userId)) {
            log.info("[AI-TASK] runAdmitted rejected reason=USER_LIMIT taskType={} businessRef={} userId={} inFlightOfUser={}",
                    request.taskType(), request.businessRef(), userId, currentOf(userId));
            throw new AiTaskRejectedException(Reason.USER_LIMIT);
        }

        long start = System.currentTimeMillis();
        log.info("[AI-TASK] start mode=sync taskType={} businessRef={} userId={} inFlightOfUser={}",
                request.taskType(), request.businessRef(), userId, currentOf(userId));
        try {
            return task.get();
        } finally {
            // finally 释放：正常返回、抛业务异常、抛准入异常都覆盖
            long executionMs = System.currentTimeMillis() - start;
            releaseUserSlot(userId);
            log.info("[AI-TASK] done mode=sync taskType={} businessRef={} userId={} executionMs={} inFlightOfUser={}",
                    request.taskType(), request.businessRef(), userId, executionMs, currentOf(userId));
        }
    }

    // ---------- worker 包装：MDC / UserContext / 名额释放 / 观测 ----------

    private Runnable wrap(AiTaskRequest request, Runnable task) {
        long submitAt = System.currentTimeMillis();
        Long userId = request.userId();

        return MdcContext.wrap(tracerProvider.getIfAvailable(), request.logContext(), () -> {
            long workerStart = System.currentTimeMillis();
            UserContext.set(userId);
            log.info("[AI-TASK] start taskType={} businessRef={} userId={} queueWaitMs={} running={} queued={} inFlightOfUser={}",
                    request.taskType(), request.businessRef(), userId, workerStart - submitAt,
                    activeCount(), queuedCount(), currentOf(userId));
            try {
                task.run();
            } finally {
                // finally 释放：正常结束、抛异常、被取消都覆盖
                long executionMs = System.currentTimeMillis() - workerStart;
                releaseUserSlot(userId);
                log.info("[AI-TASK] done taskType={} businessRef={} userId={} executionMs={} running={} queued={}",
                        request.taskType(), request.businessRef(), userId, executionMs,
                        activeCount(), queuedCount());
                // 专用线程会被复用，必须清干净，否则下一个任务读到上一个用户的 UserContext
                UserContext.clear();
            }
        });
    }

    // ---------- 每用户名额 ----------

    private boolean acquireUserSlot(Long userId) {
        AtomicBoolean acquired = new AtomicBoolean(false);
        inFlightByUser.compute(userId, (key, current) -> {
            int cur = current == null ? 0 : current;
            if (cur >= maxPerUser()) {
                return current;
            }
            acquired.set(true);
            return cur + 1;
        });
        return acquired.get();
    }

    private void releaseUserSlot(Long userId) {
        if (userId == null) {
            return;
        }
        inFlightByUser.compute(userId, (key, current) -> {
            if (current == null || current <= 1) {
                return null;
            }
            return current - 1;
        });
    }

    private int currentOf(Long userId) {
        if (userId == null) {
            return 0;
        }
        Integer current = inFlightByUser.get(userId);
        return current == null ? 0 : current;
    }

    private int maxPerUser() {
        return blogAiProperties.getTask().getMaxConcurrentPerUser();
    }

    // ---------- 全局容量（best effort 观测，不承担正确性） ----------

    /**
     * 全局是否已满。**只用于前置检查**——正确性由 {@code execute()} 的 AbortPolicy + catch 回滚保证。
     *
     * <p>读快照本身非原子（两个字段分别读），允许偏差：它只负责「尽量早拒绝」，
     * 偏差的后果仅仅是极罕见情况下降级成流内错误。
     */
    private boolean isPoolFull() {
        ThreadPoolExecutor executor = aiTaskExecutor.getThreadPoolExecutor();
        if (executor.isShutdown()) {
            return true;
        }
        return executor.getQueue().remainingCapacity() == 0
                && executor.getActiveCount() >= executor.getMaximumPoolSize();
    }

    private int activeCount() {
        return aiTaskExecutor.getThreadPoolExecutor().getActiveCount();
    }

    private int queuedCount() {
        return aiTaskExecutor.getThreadPoolExecutor().getQueue().size();
    }
}
