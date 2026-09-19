package com.hailin.blogsystem.ai.task;

import com.hailin.blogsystem.exception.AiTaskRejectedException;

import java.util.function.Supplier;

/**
 * AI 长任务的统一提交入口。
 *
 * <p>提交者只提供「这是谁的任务、是什么任务、要跑什么」，
 * 准入判断、线程分配、MDC 恢复、UserContext 设置、观测埋点都在模块内完成。
 *
 * <p><b>三种执行形态</b>：
 * <ul>
 *   <li>{@link #precheck} —— 前置检查，best effort，不改状态</li>
 *   <li>{@link #submit} —— 异步提交专用池（SSE 流式入口走这条）</li>
 *   <li>{@link #runAdmitted} —— 在当前线程同步执行（HTTP 同步入口走这条）</li>
 * </ul>
 * 三者都可能抛 {@link AiTaskRejectedException}，但语义与恢复方式不同。
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

    /**
     * 在当前线程<b>同步</b>执行，占名额但<b>不提交专用池</b>，返回任务结果。
     *
     * <p>给「必须同步拿到结果才返回」的长任务入口用——目前只有 Agent 建议确认
     * （{@code AgentRunSuggestionService.confirm}：HTTP 同步返回 Workflow 快照，前端拿它直接渲染卡片）。
     * 这类入口跑在 Tomcat 线程上，进不了专用池，但没有它就是一个<b>绕过每用户上限的后门</b>。
     *
     * <p>⚠️ <b>只加 {@link #precheck} 是假保护</b>：precheck 只读计数、不加计数，
     * 用户连点确认每次都读同一个数、每次都通过，上限完全失效。占名额必须发生在真正执行的那一刻。
     *
     * <p><b>不检查池容量</b>：这条路不往池里投任务，池满与它无关；
     * 拿池的状态去拒绝它，等于把「不占池的操作」误杀（池满时建议确认反而更该放行）。
     *
     * <p><b>不做 MDC / UserContext 包装</b>：执行发生在调用者线程，上下文本来就在。
     * 包装不仅多余，还可能覆盖调用者已有的 trace scope。
     *
     * <p>超限抛 {@link AiTaskRejectedException}（{@code USER_LIMIT} → 429）。
     * 任务体抛出的异常原样向上传播——调用方的事务语义（回滚 / 可重试）由它自己决定。
     */
    <T> T runAdmitted(AiTaskRequest request, Supplier<T> task);
}
