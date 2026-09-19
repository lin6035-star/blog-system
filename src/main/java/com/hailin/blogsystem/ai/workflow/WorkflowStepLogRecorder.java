package com.hailin.blogsystem.ai.workflow;

import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.entity.dto.AiWorkflowStep;
import com.hailin.blogsystem.service.AiWorkflowStepLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 步骤级日志基础设施：记录每一步的成功/失败、耗时、token 用量，日志失败不影响主流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowStepLogRecorder {

    private final AiWorkflowStepLogService aiWorkflowStepLogService;

    /**
     * @param stepUsage 本步的 LLM 用量（由 WorkflowTokenRecorder 暂存、WorkflowStepRunner 取走）。
     *                  允许为 null = 该步没有 LLM 调用，记 0。
     */
    public void recordStep(Long workflowRunId,
                           AiWorkflowStep step,
                           String runningMessage,
                           long durationMs,
                           RuntimeException failure,
                           TokenUsageAccumulator stepUsage) {
        if (workflowRunId == null) {
            return;
        }
        int inputTokens = stepUsage == null ? 0 : stepUsage.getPromptTokens();
        int outputTokens = stepUsage == null ? 0 : stepUsage.getCompletionTokens();
        try {
            if (failure == null) {
                aiWorkflowStepLogService.recordSuccess(
                        workflowRunId,
                        step.name(),
                        runningMessage,
                        "步骤完成，耗时 " + durationMs + "ms",
                        durationMs,
                        AiWorkflowStepLogService.LOG_TYPE_STEP,
                        inputTokens,
                        outputTokens
                );
            } else {
                aiWorkflowStepLogService.recordFailure(
                        workflowRunId,
                        step.name(),
                        runningMessage,
                        failure,
                        durationMs,
                        AiWorkflowStepLogService.LOG_TYPE_STEP,
                        inputTokens,
                        outputTokens
                );
            }
        } catch (Exception e) {
            log.warn("记录工作流步骤日志失败: step={}, runId={}", step, workflowRunId, e);
        }
    }
}
