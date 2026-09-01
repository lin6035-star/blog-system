package com.hailin.blogsystem.ai.agent;

/**
 * Agent 写动作提案（V2.4）。
 *
 * 安全模型：Agent 只能提案，后端裁判（确定性匹配）+ 用户确认后才执行。
 * LLM 只给标题类信息（planRef/stageTitle/taskTitle），不给索引——
 * 阶段/任务由后端按标题匹配定位（匹配不到拒绝或追问，不猜）。
 */
public record AgentWriteProposal(
        String actionType,
        String planRef,
        String stageTitle,
        String taskTitle,
        boolean done
) {
}
