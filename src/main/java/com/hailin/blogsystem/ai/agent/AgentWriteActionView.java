package com.hailin.blogsystem.ai.agent;

/**
 * Agent 写动作提案快照视图（V2.4，前端历史消息恢复写动作卡用）。
 *
 * status：当前 run 状态；仅 WAITING_WRITE_CONFIRM 时 proposal 非空可操作。
 */
public record AgentWriteActionView(
        String status,
        AgentWriteProposal proposal
) {
}
