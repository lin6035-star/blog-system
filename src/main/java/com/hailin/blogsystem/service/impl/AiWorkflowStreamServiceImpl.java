package com.hailin.blogsystem.service.impl;

import com.hailin.blogsystem.ai.task.AiOrchestrationTaskAdmission;
import com.hailin.blogsystem.ai.task.AiTaskRequest;
import com.hailin.blogsystem.ai.task.AiTaskType;
import com.hailin.blogsystem.ai.workflow.AiWorkflowStepEmitter;
import com.hailin.blogsystem.entity.AiChatEventType;
import com.hailin.blogsystem.entity.vo.AiChatEventVO;
import com.hailin.blogsystem.entity.vo.AiWorkflowRunVO;
import com.hailin.blogsystem.entity.vo.AiWorkflowStepLogVO;
import com.hailin.blogsystem.service.AiWorkflowRunService;
import com.hailin.blogsystem.service.AiWorkflowStreamService;
import com.hailin.blogsystem.utils.MdcContext;
import com.hailin.blogsystem.utils.UserContext;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;


import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;


@Slf4j
@Service
@RequiredArgsConstructor
public class AiWorkflowStreamServiceImpl implements AiWorkflowStreamService {

    private final AiWorkflowRunService aiWorkflowRunService;
    private final ObjectProvider<Tracer> tracerProvider;
    private final AiOrchestrationTaskAdmission admission;


    @Override
    public Flux<AiChatEventVO> approve(Long id) {
        return streamAction(id,"APPROVE", emitter -> aiWorkflowRunService.approve(id,emitter));
    }

    @Override
    public Flux<AiChatEventVO> reject(Long id, String feedback) {
        return streamAction(id,"REJECT", emitter -> aiWorkflowRunService.reject(id,feedback,emitter));
    }

    @Override
    public Flux<AiChatEventVO> retry(Long id) {
        return streamAction(id,"RETRY", emitter -> aiWorkflowRunService.retry(id,emitter));
    }

    @Override
    public Flux<AiChatEventVO> approve(
            Long id,
            String idempotencyKey
    ) {
        return streamAction(
                id,
                "APPROVE",
                emitter -> aiWorkflowRunService.approve(
                        id,
                        idempotencyKey,
                        emitter
                )
        );
    }

    @Override
    public Flux<AiChatEventVO> reject(
            Long id,
            String feedback,
            String idempotencyKey
    ) {
        return streamAction(
                id,
                "REJECT",
                emitter -> aiWorkflowRunService.reject(
                        id,
                        feedback,
                        idempotencyKey,
                        emitter
                )
        );
    }

    @Override
    public Flux<AiChatEventVO> retry(
            Long id,
            String idempotencyKey
    ) {
        return streamAction(
                id,
                "RETRY",
                emitter -> aiWorkflowRunService.retry(
                        id,
                        idempotencyKey,
                        emitter
                )
        );
    }

    private Flux<AiChatEventVO> streamAction(
            Long id,
            String action,
            Function<AiWorkflowStepEmitter, AiWorkflowRunVO> actionInvoker
    ) {
        Long userId = UserContext.get();

        // V4⑥ 可观测性：异步提交发生在订阅线程（无 MDC），
        // 在请求线程先抓日志上下文，执行线程由准入模块统一恢复（MDC + TraceContext；
        // 只恢复 MDC 会在后续 tracing scope 切换时丢 traceId）。
        MdcContext.LogContext logContext =
                MdcContext.captureWithTrace(tracerProvider.getIfAvailable(), MdcContext.capture());

        // 准入前置：在构造任何 SSE 事件之前拒绝，前端才能拿到 HTTP 429/503 而不是「连接意外中断」。
        // （这条路径本身没有前置事件、订阅时抛也能返回状态码，见 SseAdmissionProbeTests；
        //   前置是为了拒绝更早、日志更清晰。）
        AiTaskType taskType = toTaskType(action);
        String businessRef = id + ":" + action;
        admission.precheck(userId, taskType, businessRef);

        AiTaskRequest taskRequest = AiTaskRequest.of(userId, taskType, businessRef, logContext);

        return Flux.create(sink -> {
            long subscribeAt = System.currentTimeMillis();
            sink.onCancel(() -> log.info(
                    "[PERF-WORKFLOW] stream_cancelled runId={} action={}",
                    id,
                    action
            ));

            log.info(
                    "[PERF-WORKFLOW] stream_subscribe runId={} action={}",
                    id,
                    action
            );

            admission.submit(taskRequest, () -> {
                long workerStart = System.currentTimeMillis();

                log.info(
                        "[PERF-WORKFLOW] worker_start runId={} action={} queueWaitMs={}",
                        id,
                        action,
                        workerStart - subscribeAt
                );

                AtomicBoolean firstContentLogged =
                        new AtomicBoolean(false);

                try {
                    AiWorkflowStepEmitter emitter = new AiWorkflowStepEmitter() {
                        @Override
                        public void emit(String step, String status, String message) {
                            // RUNNING 等无耗时/用量的阶段走这条
                            safeNext(sink, workflowStepEvent(id, action, step, status, message,
                                    null, null, null));
                        }

                        /**
                         * 带耗时与 token 的步骤事件（runStep 的 SUCCESS / FAILED 走这条）。
                         *
                         * 前端实时日志原先只有 message，于是把「步骤完成，耗时 61515ms」塞进
                         * inputSummary、耗时栏显示 `—`、token 行不渲染，要等 STOP 拼数据库日志才正常。
                         * 这里把三者作为结构化字段下发，实时即可正确展示。
                         */
                        @Override
                        public void emit(String step, String status, String message,
                                         Long durationMs, Integer inputTokens, Integer outputTokens) {
                            safeNext(sink, workflowStepEvent(id, action, step, status, message,
                                    durationMs, inputTokens, outputTokens));
                        }

                        @Override
                        public void emitContent(String step, String field, String delta) {
                            if (sink.isCancelled()) {
                                return;
                            }
                            if (firstContentLogged.compareAndSet(false, true)) {
                                log.info(
                                        "[PERF-WORKFLOW] sse_first_content runId={} action={} step={} field={} deltaChars={}",
                                        id,
                                        action,
                                        step,
                                        field,
                                        delta == null ? 0 : delta.length()
                                );
                            }
                            safeNext(sink, workflowContentDeltaEvent(id, step, field, delta));
                        }
                    };

                    long actionStart = System.currentTimeMillis();

                    log.info(
                            "[PERF-WORKFLOW] action_start runId={} action={}",
                            id,
                            action
                    );

                    AiWorkflowRunVO workflow =
                            actionInvoker.apply(emitter);

                    log.info(
                            "[PERF-WORKFLOW] action_end runId={} action={} status={} durationMs={}",
                            id,
                            action,
                            workflow.getStatus(),
                            System.currentTimeMillis() - actionStart
                    );

                    List<AiWorkflowStepLogVO> stepLogs = aiWorkflowRunService.listStepLogs(id);

                    if ("FAILED".equals(workflow.getStatus())) {
                        safeNext(sink, workflowErrorEvent(workflow, stepLogs));
                    } else {
                        safeNext(sink, workflowStopEvent(workflow, stepLogs));
                    }

                    safeComplete(sink);
                } catch (Throwable e) {
                    if (!sink.isCancelled()) {
                        safeNext(sink, exceptionEvent(id, action, e));
                        safeComplete(sink);
                    } else {
                        log.info(
                                "Workflow SSE 客户端已断开，忽略异常事件发送: runId={}, action={}",
                                id,
                                action
                        );
                    }
                }
            });
        });
    }

    /** Workflow 动作 → 任务类型（准入观测与拒绝文案用）。 */
    private AiTaskType toTaskType(String action) {
        return switch (action) {
            case "REJECT" -> AiTaskType.WORKFLOW_REJECT;
            case "RETRY" -> AiTaskType.WORKFLOW_RETRY;
            default -> AiTaskType.WORKFLOW_APPROVE;
        };
    }

    private void safeNext(FluxSink<AiChatEventVO> sink, AiChatEventVO event) {
        if (!sink.isCancelled()) {
            sink.next(event);
        }
    }

    private void safeComplete(FluxSink<AiChatEventVO> sink) {
        if (!sink.isCancelled()) {
            sink.complete();
        }
    }

    private AiChatEventVO workflowContentDeltaEvent(Long id, String step, String field, String delta) {
        Map<String, Object> data = new HashMap<>();
        data.put("workflowRunId", String.valueOf(id));
        data.put("step", step);
        data.put("field", field);
        data.put("delta", delta);

        return AiChatEventVO.builder()
                .eventType(AiChatEventType.WORKFLOW_CONTENT_DELTA.getValue())
                .eventData(data)
                .build();
    }

    private AiChatEventVO workflowStepEvent(Long id, String action, String step, String status, String message,
                                            Long durationMs, Integer inputTokens, Integer outputTokens) {
        Map<String, Object> data = new HashMap<>();
        data.put("workflowRunId", String.valueOf(id));
        data.put("action", action);
        data.put("status", status);
        data.put("message", message);
        if (step != null) {
            data.put("step", step);
        }
        // 结构化元数据：前端实时步骤日志据此显示耗时与 token，不必等 SSE STOP 再拼数据库日志。
        // 只在有值时下发（RUNNING 事件这三项都为空）
        if (durationMs != null) {
            data.put("durationMs", durationMs);
        }
        if (inputTokens != null) {
            data.put("inputTokens", inputTokens);
        }
        if (outputTokens != null) {
            data.put("outputTokens", outputTokens);
        }

        return AiChatEventVO.builder()
                .eventType(AiChatEventType.WORKFLOW_STEP.getValue())
                .eventData(data)
                .build();
    }

    /** 根据当前状态 + 操作推断下一步要执行的步骤（与 CreateArticleWorkflowHandler 的推进逻辑一致） */
    private String nextStepByAction(String action, AiWorkflowRunVO run) {
        if (run == null || run.getStatus() == null) {
            return null;
        }
        switch (action) {
            case "APPROVE":
                //确认大纲 → 生成草稿；确认草稿 → 质量检查；确认填充 → 填充编辑器
                if ("WAITING_OUTLINE_CONFIRM".equals(run.getStatus())) return "GENERATE_DRAFT";
                if ("WAITING_DRAFT_CONFIRM".equals(run.getStatus())) return "QUALITY_CHECK";
                if ("WAITING_FILL_CONFIRM".equals(run.getStatus())) return "FILL_ARTICLE";
                return null;
            case "REJECT":
                //补充需求 → 重新分析；打回大纲 → 重新生成大纲；打回草稿/填充 → 重写草稿
                if ("WAITING_REQUIREMENT_CONFIRM".equals(run.getStatus())) return "REQUIREMENT_ANALYZE";
                if ("WAITING_OUTLINE_CONFIRM".equals(run.getStatus())) return "GENERATE_OUTLINE";
                if ("WAITING_DRAFT_CONFIRM".equals(run.getStatus())) return "GENERATE_DRAFT";
                if ("WAITING_FILL_CONFIRM".equals(run.getStatus())) return "GENERATE_DRAFT";
                return null;
            case "RETRY":
                //重试失败步骤：currentStep 保留的就是失败时的步骤
                return run.getCurrentStep();
            default:
                return null;
        }
    }

    private AiChatEventVO workflowStopEvent(AiWorkflowRunVO workflow, List<AiWorkflowStepLogVO> stepLogs) {
        Map<String, Object> data = new HashMap<>();
        data.put("workflow", workflow);
        data.put("stepLogs", stepLogs);
        if (workflow.getEditorAction() != null) {
            data.put("editorAction", workflow.getEditorAction());
        }

        return AiChatEventVO.builder()
                .eventType(AiChatEventType.WORKFLOW_STOP.getValue())
                .eventData(data)
                .build();
    }

    private AiChatEventVO workflowErrorEvent(AiWorkflowRunVO workflow, List<AiWorkflowStepLogVO> stepLogs) {
        Map<String, Object> data = new HashMap<>();
        data.put("workflow", workflow);
        data.put("stepLogs", stepLogs);
        data.put("message", workflow.getErrorMessage());

        return AiChatEventVO.builder()
                .eventType(AiChatEventType.WORKFLOW_ERROR.getValue())
                .eventData(data)
                .build();
    }

    private AiChatEventVO exceptionEvent(Long id, String action, Throwable e) {
        Map<String, Object> data = new HashMap<>();
        data.put("workflowRunId", String.valueOf(id));
        data.put("action", action);
        data.put("message", e.getMessage());

        return AiChatEventVO.builder()
                .eventType(AiChatEventType.WORKFLOW_ERROR.getValue())
                .eventData(data)
                .build();
    }
}
