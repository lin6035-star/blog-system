package com.hailin.blogsystem.service.impl;

import com.hailin.blogsystem.ai.workflow.AiWorkflowStepEmitter;
import com.hailin.blogsystem.entity.AiChatEventType;
import com.hailin.blogsystem.entity.vo.AiChatEventVO;
import com.hailin.blogsystem.entity.vo.AiWorkflowRunVO;
import com.hailin.blogsystem.entity.vo.AiWorkflowStepLogVO;
import com.hailin.blogsystem.service.AiWorkflowRunService;
import com.hailin.blogsystem.service.AiWorkflowStreamService;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import com.hailin.blogsystem.ai.workflow.AiWorkflowStepEmitter;


import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;


@Service
@RequiredArgsConstructor
public class AiWorkflowStreamServiceImpl implements AiWorkflowStreamService {

    private final AiWorkflowRunService aiWorkflowRunService;


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

    private Flux<AiChatEventVO> streamAction(
            Long id,
            String action,
            Function<AiWorkflowStepEmitter, AiWorkflowRunVO> actionInvoker
    ) {
        Long userId = UserContext.get();

        return Flux.create(sink -> Schedulers.boundedElastic().schedule(() -> {
            UserContext.set(userId);
            try {
                AiWorkflowStepEmitter emitter = new AiWorkflowStepEmitter() {
                    @Override
                    public void emit(String step, String status, String message) {
                        sink.next(workflowStepEvent(id, action, step, status, message));
                    }

                    @Override
                    public void emitContent(String step, String field, String delta) {
                        sink.next(workflowContentDeltaEvent(id, step, field, delta));
                    }
                };

                AiWorkflowRunVO workflow = actionInvoker.apply(emitter);
                List<AiWorkflowStepLogVO> stepLogs = aiWorkflowRunService.listStepLogs(id);

                if ("FAILED".equals(workflow.getStatus())) {
                    sink.next(workflowErrorEvent(workflow, stepLogs));
                } else {
                    sink.next(workflowStopEvent(workflow, stepLogs));
                }

                sink.complete();
            } catch (Throwable e) {
                sink.next(exceptionEvent(id, action, e));
                sink.complete();
            } finally {
                UserContext.clear();
            }
        }));
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

    private AiChatEventVO workflowStepEvent(Long id, String action, String step, String status, String message) {
        Map<String, Object> data = new HashMap<>();
        data.put("workflowRunId", String.valueOf(id));
        data.put("action", action);
        data.put("status", status);
        data.put("message", message);
        if (step != null) {
            data.put("step", step);
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
