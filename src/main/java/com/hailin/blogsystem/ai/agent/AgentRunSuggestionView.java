package com.hailin.blogsystem.ai.agent;

/**
 * Agent Run 建议快照视图（V2.1，前端历史消息恢复建议卡用）。
 *
 * status：当前 run 状态；仅 WAITING_WORKFLOW_CONFIRM 时 suggestion 非空可操作。
 */
public record AgentRunSuggestionView(
        String status,
        AgentWorkflowSuggestion suggestion
) {
}
