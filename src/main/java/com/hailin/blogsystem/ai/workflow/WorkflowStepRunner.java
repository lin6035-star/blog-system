package com.hailin.blogsystem.ai.workflow;

import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.entity.dto.AiWorkflowStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 步骤执行模板：emit RUNNING → 计时执行 → emit SUCCESS / FAILED → 记 StepLog → 异常继续抛出。
 * 只负责"把一步跑干净、记清楚、失败可见"，不决定下一步跑什么、失败后怎么恢复。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowStepRunner {

    private final WorkflowStepLogRecorder workflowStepLogRecorder;
    private final WorkflowTokenRecorder workflowTokenRecorder;

    //workflowRunId 为 null（create 时 run 还没入库）则只 emit 不落库，步骤日志由操作级日志兜底
    public <T> T run(
            Long workflowRunId,
            AiWorkflowStep step,
            String runningMessage,
            Supplier<T> action,
            AiWorkflowStepEmitter emitter
    ) {
        AiWorkflowStepEmitter safeEmitter =
                emitter == null
                        ? AiWorkflowStepEmitter.noop()
                        : emitter;

        long enteredAt = System.currentTimeMillis();

        log.info(
                "[PERF-WORKFLOW] step_enter runId={} step={}",
                workflowRunId,
                step
        );

        safeEmitter.emit(
                step.name(),
                "RUNNING",
                runningMessage
        );

        long actionStart = System.currentTimeMillis();

        log.info(
                "[PERF-WORKFLOW] step_action_start runId={} step={} emitRunningMs={}",
                workflowRunId,
                step,
                actionStart - enteredAt
        );

        try {
            T result = action.get();

            long durationMs =
                    System.currentTimeMillis() - actionStart;

            // 先取走本步用量：emit 与落库**共用同一份**——drain 会清空缓冲，只能取一次。
            // 无条件取走：即便 runId 为 null（不落库）也要清空，否则残留会算到下一步头上
            TokenUsageAccumulator stepUsage = workflowTokenRecorder.drainStepBuffer();

            // 带元数据 emit：前端实时即可显示耗时与 token，不必等 STOP 拼数据库日志
            safeEmitter.emit(
                    step.name(),
                    "SUCCESS",
                    "步骤完成，耗时 " + durationMs + "ms",
                    durationMs,
                    stepUsage.getPromptTokens(),
                    stepUsage.getCompletionTokens()
            );

            workflowStepLogRecorder.recordStep(
                    workflowRunId,
                    step,
                    runningMessage,
                    durationMs,
                    null,
                    stepUsage
            );

            log.info(
                    "[PERF-WORKFLOW] step_end runId={} step={} status=SUCCESS durationMs={}",
                    workflowRunId,
                    step,
                    durationMs
            );

            return result;
        } catch (RuntimeException e) {
            long durationMs =
                    System.currentTimeMillis() - actionStart;

            // 失败路径同样要取走：LLM 可能已经跑完（token 已消耗）才在后续步骤炸掉
            TokenUsageAccumulator stepUsage = workflowTokenRecorder.drainStepBuffer();

            safeEmitter.emit(
                    step.name(),
                    "FAILED",
                    e.getMessage(),
                    durationMs,
                    stepUsage.getPromptTokens(),
                    stepUsage.getCompletionTokens()
            );

            workflowStepLogRecorder.recordStep(
                    workflowRunId,
                    step,
                    runningMessage,
                    durationMs,
                    e,
                    stepUsage
            );

            log.warn(
                    "[PERF-WORKFLOW] step_end runId={} step={} status=FAILED durationMs={} error={}",
                    workflowRunId,
                    step,
                    durationMs,
                    e.getMessage()
            );

            throw e;
        }
    }
}
