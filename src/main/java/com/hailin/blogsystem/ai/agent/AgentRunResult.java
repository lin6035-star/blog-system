package com.hailin.blogsystem.ai.agent;

import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;

/**
 * Agent Run 执行结果（返回给聊天链路）。
 *
 * totalTokens：本次 run 循环的 token 消耗（含决策链 + 证据收敛门，**不含意图分类器**）。
 * 聊天链路把它加上分类器的消耗，一起写进 ai_messages.token_count——那个字段的语义是
 * 「这条消息的完整成本」，而 ai_agent_runs.total_tokens 只是 Agent 循环内部成本。
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
        int totalTokens,
        AgentWorkflowSuggestion pendingWorkflowSuggestion,
        AgentWriteProposal pendingWriteAction
) {

    public static AgentRunResult of(
            Long agentRunId,
            AiAgentRunStatus status,
            String finalAnswer,
            int usedSteps,
            Integer totalTokens
    ) {
        return new AgentRunResult(agentRunId, status, finalAnswer, usedSteps, safe(totalTokens), null, null);
    }

    public static AgentRunResult of(
            Long agentRunId,
            AiAgentRunStatus status,
            String finalAnswer,
            int usedSteps,
            Integer totalTokens,
            AgentWorkflowSuggestion pendingWorkflowSuggestion
    ) {
        return new AgentRunResult(agentRunId, status, finalAnswer, usedSteps, safe(totalTokens),
                pendingWorkflowSuggestion, null);
    }

    public static AgentRunResult of(
            Long agentRunId,
            AiAgentRunStatus status,
            String finalAnswer,
            int usedSteps,
            Integer totalTokens,
            AgentWorkflowSuggestion pendingWorkflowSuggestion,
            AgentWriteProposal pendingWriteAction
    ) {
        return new AgentRunResult(agentRunId, status, finalAnswer, usedSteps, safe(totalTokens),
                pendingWorkflowSuggestion, pendingWriteAction);
    }

    /** run 的 token 字段是 Integer（老数据/未统计时为 null），出口统一归一为 0。 */
    private static int safe(Integer totalTokens) {
        return totalTokens == null ? 0 : totalTokens;
    }
}
