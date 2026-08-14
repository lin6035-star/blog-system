package com.hailin.blogsystem.ai.workflow;

import com.hailin.blogsystem.entity.AiWorkflowRun;

public interface WorkflowHandler {

    String workflowType();

    AiWorkflowAdvanceResult approve(AiWorkflowRun run, AiWorkflowStepEmitter emitter);

    AiWorkflowAdvanceResult reject(AiWorkflowRun run, String feedback, AiWorkflowStepEmitter emitter);

    AiWorkflowAdvanceResult retry(AiWorkflowRun run, AiWorkflowStepEmitter emitter);
}
