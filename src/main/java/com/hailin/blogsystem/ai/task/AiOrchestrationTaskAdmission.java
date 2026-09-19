package com.hailin.blogsystem.ai.task;

import com.hailin.blogsystem.exception.AiTaskRejectedException;

/**
 * AI 长任务的统一提交入口。
 *
 * <p>提交者只提供「这是谁的任务、是什么任务、要跑什么」，
 * 准入判断、线程分配、MDC 恢复、UserContext 设置、观测埋点都在模块内完成。
 *
 * <p><b>两段式</b>：{@link #precheck} 在构造任何 SSE 事件之前调用
 * （尽量把拒绝提前到 HTTP 状态码），{@link #submit} 在订阅时调用（权威判定）。
 * 两段都可能抛 {@link AiTaskRejectedException}，但语义不同。
 */
public interface AiOrchestrationTaskAdmission {

    /**
     * 前置检查（<b>best effort，不做任何状态变更</b>）。必须在构造任何 SSE 事件之前调用。
     *
     * <p>超限抛 {@link AiTaskRejectedException}（{@code USER_LIMIT} → 429 /
     * {@code POOL_FULL} → 503），让 {@code GlobalExceptionHandler} 能在响应提交前改状态码。
     *
     * <p>只收 {@code userId} 而不是整个 {@link AiTaskRequest}：前置检查用不到 {@code logContext}，
     * 而调用点往往需要把检查放在**日志上下文捕获之前**（比如 {@code save(userMessage)} 之前，
     * 否则拒绝后会留下孤立消息）。
     *
     * <p>⚠️ 这是<b>优化</b>不是保证：它放宽的是「拒绝的时机」，不是「上限本身」。
     * 检查通过不代表提交一定成功（池状态随时在变）——所以名额的增减不在这里。
     */
    void precheck(Long userId, AiTaskType taskType, String businessRef);

    /**
     * 准入并提交（订阅时调用）。<b>这是权威判定</b>：
     * 原子占用用户名额 → 提交线程池；任一步失败即回滚并抛 {@link AiTaskRejectedException}，
     * 由调用方转成<b>流内错误事件</b>（此时响应已提交，状态码改不动了）。
     *
     * <p>用户上限在这里<b>严格执行</b>——竞态的结果是「这个任务被拒」，不是「多跑一个」。
     *
     * <p>调用方不拿 Disposable：连接断开不是业务取消，不能把 Reactor dispose 冒充成 LLM 取消。
     */
    void submit(AiTaskRequest request, Runnable task);
}
