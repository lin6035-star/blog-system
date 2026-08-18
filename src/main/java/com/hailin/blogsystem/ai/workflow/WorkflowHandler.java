package com.hailin.blogsystem.ai.workflow;

import com.hailin.blogsystem.entity.AiWorkflowRun;

public interface WorkflowHandler {

    String workflowType();

    //初始链路：create 只初始化 run，Service save 后调用本方法执行首段步骤
    AiWorkflowAdvanceResult runInitialSteps(AiWorkflowRun run, AiWorkflowStepEmitter emitter);

    AiWorkflowAdvanceResult approve(AiWorkflowRun run, AiWorkflowStepEmitter emitter);

    AiWorkflowAdvanceResult reject(AiWorkflowRun run, String feedback, AiWorkflowStepEmitter emitter);

    AiWorkflowAdvanceResult retry(AiWorkflowRun run, AiWorkflowStepEmitter emitter);

    /**
     * 可预期业务拒绝 → 友好 AI 回复文案（如"只能优化自己的文章"）。
     * 返回 null 表示没有友好回复（真异常），入口照旧 sink.error。
     * 业务拒绝文案属于各 Workflow 的业务规则，有拒绝场景的 Handler 覆写即可。
     */
    default String buildRejectedMessage(Throwable e) {
        return null;
    }
}
