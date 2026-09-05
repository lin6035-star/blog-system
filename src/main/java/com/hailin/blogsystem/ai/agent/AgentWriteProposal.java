package com.hailin.blogsystem.ai.agent;

/**
 * Agent 写动作提案（V2.4）。
 *
 * 安全模型：Agent 只能提案，后端裁判（确定性匹配）+ 用户确认后才执行。
 * LLM 只给标题类信息（planRef/stageTitle/taskTitle/newTitle），不给索引——
 * 阶段/任务由后端按标题匹配定位（匹配不到拒绝或追问，不猜）。
 */
public record AgentWriteProposal(
        String actionType,
        String planRef,
        String stageTitle,
        String taskTitle,
        boolean done,
        String newTitle
) {

    /** 写动作类型（V2.4）：勾选 / 取消勾选已有任务（done 生效） */
    public static final String TYPE_UPDATE_TASK_DONE = "UPDATE_TASK_DONE";

    /** 写动作类型（V3.1）：向计划指定阶段追加用户点名的新任务（done 忽略） */
    public static final String TYPE_ADD_LEARNING_TASK = "ADD_LEARNING_TASK";

    /** 写动作类型（V3.3）：把已有任务重命名（taskTitle=旧名定位，newTitle=用户给的新名，done 忽略） */
    public static final String TYPE_UPDATE_LEARNING_TASK = "UPDATE_LEARNING_TASK";
}
