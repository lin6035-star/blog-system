package com.hailin.blogsystem.ai.workflow;

import com.hailin.blogsystem.entity.dto.AiWorkflowStep;
import com.hailin.blogsystem.service.AiWorkflowStepLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 步骤级日志基础设施：记录每一步的成功/失败、耗时，日志失败不影响主流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowStepLogRecorder {

    private final AiWorkflowStepLogService aiWorkflowStepLogService;

    //workflowRunId 为 null（create 时 run 还没入库）则跳过，由操作级日志兜底
    public void recordStep(Long workflowRunId,
                           AiWorkflowStep step,
                           String runningMessage,
                           long durationMs,
                           RuntimeException failure) {
        if (workflowRunId == null) {
            return;
        }
        try {
            if (failure == null) {
                aiWorkflowStepLogService.recordSuccess(
                        workflowRunId,
                        step.name(),
                        runningMessage,
                        "步骤完成，耗时 " + durationMs + "ms",
                        durationMs,
                        AiWorkflowStepLogService.LOG_TYPE_STEP
                );
            } else {
                aiWorkflowStepLogService.recordFailure(
                        workflowRunId,
                        step.name(),
                        runningMessage,
                        failure,
                        durationMs,
                        AiWorkflowStepLogService.LOG_TYPE_STEP
                );
            }
        } catch (Exception e) {
            log.warn("记录工作流步骤日志失败: step={}, runId={}", step, workflowRunId, e);
        }
    }
}
