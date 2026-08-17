package com.hailin.blogsystem.ai.workflow;

import com.hailin.blogsystem.entity.AiEditorCommand;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.dto.AiWorkflowConfirmationType;
import com.hailin.blogsystem.entity.dto.AiWorkflowStatus;
import com.hailin.blogsystem.entity.dto.AiWorkflowStep;

import java.util.Map;

/**
 * Workflow Handler 抽象基类：approve / reject / retry 的模板骨架 + 收尾工具。
 *
 * 骨架（模板方法，final）：
 *   解析状态/上下文/stepResults → 分发到业务方法 doXxx
 * 收尾工具（子类业务分支调用）：
 *   waitForConfirm 停在等待确认态：发确认卡片 → 置状态/步骤 → 落 contextJson → 刷新时间
 *   finish         消费确认并完成：清卡片 → COMPLETED → 落 contextJson → 返回（可带 editorAction）
 *
 * 确认卡片生命周期（发卡 → 消费清卡）统一收进骨架，Handler 不再手写。
 */
public abstract class AbstractWorkflowHandler implements WorkflowHandler {

    protected final WorkflowContextSupport workflowContextSupport;
    protected final WorkflowStatusSupport workflowStatusSupport;
    protected final WorkflowStepRunner workflowStepRunner;

    protected AbstractWorkflowHandler(
            WorkflowContextSupport workflowContextSupport,
            WorkflowStatusSupport workflowStatusSupport,
            WorkflowStepRunner workflowStepRunner
    ) {
        this.workflowContextSupport = workflowContextSupport;
        this.workflowStatusSupport = workflowStatusSupport;
        this.workflowStepRunner = workflowStepRunner;
    }

    // ==================== 模板骨架 ====================

    @Override
    public final AiWorkflowAdvanceResult approve(AiWorkflowRun run, AiWorkflowStepEmitter emitter) {
        AiWorkflowStepEmitter safeEmitter = emitter == null ? AiWorkflowStepEmitter.noop() : emitter;
        AiWorkflowStatus status = workflowStatusSupport.parseStatus(run.getStatus());
        Map<String, Object> context = workflowContextSupport.parseContext(run.getContextJson());
        Map<String, Object> stepResults = workflowContextSupport.getStepResults(context);
        return doApprove(run, status, context, stepResults, safeEmitter);
    }

    /** 业务分支：按当前状态推进，非法状态抛"当前状态不允许同意操作" */
    protected abstract AiWorkflowAdvanceResult doApprove(
            AiWorkflowRun run,
            AiWorkflowStatus status,
            Map<String, Object> context,
            Map<String, Object> stepResults,
            AiWorkflowStepEmitter emitter
    );

    @Override
    public final AiWorkflowAdvanceResult reject(AiWorkflowRun run, String feedback, AiWorkflowStepEmitter emitter) {
        AiWorkflowStepEmitter safeEmitter = emitter == null ? AiWorkflowStepEmitter.noop() : emitter;
        String normalizedFeedback = workflowContextSupport.normalizeRequired(feedback, "修改意见不能为空");
        AiWorkflowStatus status = workflowStatusSupport.parseStatus(run.getStatus());
        Map<String, Object> context = workflowContextSupport.parseContext(run.getContextJson());
        Map<String, Object> stepResults = workflowContextSupport.getStepResults(context);
        workflowContextSupport.appendFeedback(context, run.getCurrentStep(), run.getStatus(), normalizedFeedback);
        return doReject(run, status, context, stepResults, normalizedFeedback, safeEmitter);
    }

    /** 业务分支：带用户反馈重做当前阶段，非法状态抛"当前状态不允许提交修改意见" */
    protected abstract AiWorkflowAdvanceResult doReject(
            AiWorkflowRun run,
            AiWorkflowStatus status,
            Map<String, Object> context,
            Map<String, Object> stepResults,
            String feedback,
            AiWorkflowStepEmitter emitter
    );

    @Override
    public final AiWorkflowAdvanceResult retry(AiWorkflowRun run, AiWorkflowStepEmitter emitter) {
        AiWorkflowStepEmitter safeEmitter = emitter == null ? AiWorkflowStepEmitter.noop() : emitter;
        AiWorkflowStep step = workflowStatusSupport.parseStep(run.getCurrentStep());
        Map<String, Object> context = workflowContextSupport.parseContext(run.getContextJson());
        Map<String, Object> stepResults = workflowContextSupport.getStepResults(context);
        return doRetry(run, step, context, stepResults, safeEmitter);
    }

    /** 业务分支：从失败步骤原地恢复，非法步骤抛"当前步骤不支持重试" */
    protected abstract AiWorkflowAdvanceResult doRetry(
            AiWorkflowRun run,
            AiWorkflowStep step,
            Map<String, Object> context,
            Map<String, Object> stepResults,
            AiWorkflowStepEmitter emitter
    );

    // ==================== 收尾工具 ====================

    /** 停在等待确认态：发卡片 → 置状态/步骤 → 落 contextJson → 清错误信息 → 刷新时间 */
    protected AiWorkflowAdvanceResult waitForConfirm(
            AiWorkflowRun run,
            Map<String, Object> context,
            AiWorkflowStatus status,
            AiWorkflowStep step,
            AiWorkflowConfirmationType confirmationType
    ) {
        return waitForConfirm(run, context, status, step, confirmationType, null);
    }

    /** 同上，追问类确认卡片带 question */
    protected AiWorkflowAdvanceResult waitForConfirm(
            AiWorkflowRun run,
            Map<String, Object> context,
            AiWorkflowStatus status,
            AiWorkflowStep step,
            AiWorkflowConfirmationType confirmationType,
            String question
    ) {
        workflowContextSupport.putConfirmation(context, confirmationType, step.name(), question);
        run.setStatus(status.name());
        run.setCurrentStep(step.name());
        run.setContextJson(workflowContextSupport.toJson(context));
        run.setErrorMessage(null);
        workflowContextSupport.touch(run);
        return AiWorkflowAdvanceResult.of(run);
    }

    /** 消费确认并完成：清卡片 → COMPLETED → 落 contextJson；editorAction 非空则随结果返回（填充编辑器） */
    protected AiWorkflowAdvanceResult finish(
            AiWorkflowRun run,
            Map<String, Object> context,
            AiWorkflowStep step,
            AiEditorCommand editorAction
    ) {
        workflowContextSupport.clearConfirmation(context);
        run.setStatus(AiWorkflowStatus.COMPLETED.name());
        run.setCurrentStep(step.name());
        run.setContextJson(workflowContextSupport.toJson(context));
        run.setErrorMessage(null);
        workflowContextSupport.touch(run);
        return editorAction == null
                ? AiWorkflowAdvanceResult.of(run)
                : AiWorkflowAdvanceResult.withEditorAction(run, editorAction);
    }
}
