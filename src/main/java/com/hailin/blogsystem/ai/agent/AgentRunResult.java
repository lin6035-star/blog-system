package com.hailin.blogsystem.ai.agent;

import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;

/**
 * Agent Run 执行结果（返回给聊天链路）。
 *
 * pendingWorkflowSuggestion：仅 WAITING_WORKFLOW_CONFIRM 终态非空（V2.1），
 * pendingWriteAction：仅 WAITING_WRITE_CONFIRM 终态非空（V2.4），
 * 由聊天链路透出给前端渲染确认卡。
 */
public record AgentRunResult(
        Long agentRunId,
        AiAgentRunStatus status,
        String finalAnswer,
        int usedSteps,
        AgentWorkflowSuggestion pendingWorkflowSuggestion,
        AgentWriteProposal pendingWriteAction
) {

    public static AgentRunResult of(
            Long agentRunId,
            AiAgentRunStatus status,
            String finalAnswer,
            int usedSteps
    ) {
        return new AgentRunResult(agentRunId, status, finalAnswer, usedSteps, null, null);
    }

    public static AgentRunResult of(
            Long agentRunId,
            AiAgentRunStatus status,
            String finalAnswer,
            int usedSteps,
            AgentWorkflowSuggestion pendingWorkflowSuggestion
    ) {
        return new AgentRunResult(agentRunId, status, finalAnswer, usedSteps, pendingWorkflowSuggestion, null);
    }

    public static AgentRunResult of(
            Long agentRunId,
            AiAgentRunStatus status,
            String finalAnswer,
            int usedSteps,
            AgentWorkflowSuggestion pendingWorkflowSuggestion,
            AgentWriteProposal pendingWriteAction
    ) {
        return new AgentRunResult(agentRunId, status, finalAnswer, usedSteps, pendingWorkflowSuggestion, pendingWriteAction);
    }
}
