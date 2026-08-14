package com.hailin.blogsystem.ai.workflow;

import com.hailin.blogsystem.entity.dto.AiWorkflowStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 步骤执行模板：emit RUNNING → 计时执行 → emit SUCCESS / FAILED → 记 StepLog → 异常继续抛出。
 * 只负责"把一步跑干净、记清楚、失败可见"，不决定下一步跑什么、失败后怎么恢复。
 */
@Component
@RequiredArgsConstructor
public class WorkflowStepRunner {

    private final WorkflowStepLogRecorder workflowStepLogRecorder;

    //workflowRunId 为 null（create 时 run 还没入库）则只 emit 不落库，步骤日志由操作级日志兜底
    public <T> T run(Long workflowRunId,
                     AiWorkflowStep step,
                     String runningMessage,
                     Supplier<T> action,
                     AiWorkflowStepEmitter emitter) {
        AiWorkflowStepEmitter safeEmitter = emitter == null ? AiWorkflowStepEmitter.noop() : emitter;

        safeEmitter.emit(step.name(), "RUNNING", runningMessage);
        long start = System.currentTimeMillis();

        try {
            T result = action.get();
            long durationMs = System.currentTimeMillis() - start;
            safeEmitter.emit(step.name(), "SUCCESS", "步骤完成，耗时 " + durationMs + "ms");
            workflowStepLogRecorder.recordStep(workflowRunId, step, runningMessage, durationMs, null);
            return result;
        } catch (RuntimeException e) {
            long durationMs = System.currentTimeMillis() - start;
            safeEmitter.emit(step.name(), "FAILED", e.getMessage());
            workflowStepLogRecorder.recordStep(workflowRunId, step, runningMessage, durationMs, e);
            throw e;
        }
    }
}
