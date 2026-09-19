package com.hailin.blogsystem.ai.task;

import com.hailin.blogsystem.utils.MdcContext;

/**
 * 长任务的提交上下文：提交者只说清「这是谁的任务、是什么任务、怎么关联日志、要不要恢复 MDC」，
 * 准入判断、线程分配、UserContext 设置、观测埋点都由 {@link AiOrchestrationTaskAdmission} 完成。
 *
 * @param userId      准入计数的主体；游客为 {@code null}（不计用户级名额）
 * @param taskType    任务类型，用于观测与拒绝文案
 * @param businessRef 日志关联标识。创建前拿不到 runId 的路径用 {@code requestId:sessionId}；
 *                    已有 Workflow 的动作用 {@code runId:action}
 * @param logContext  请求线程抓好的日志上下文，worker 起来后由
 *                    {@link MdcContext#wrap} 恢复（MDC + traceId）
 */
public record AiTaskRequest(
        Long userId,
        AiTaskType taskType,
        String businessRef,
        MdcContext.LogContext logContext
) {

    public static AiTaskRequest of(Long userId,
                                   AiTaskType taskType,
                                   String businessRef,
                                   MdcContext.LogContext logContext) {
        return new AiTaskRequest(userId, taskType, businessRef, logContext);
    }
}
