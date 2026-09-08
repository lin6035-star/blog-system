package com.hailin.blogsystem.ai.agent;

/**
 * Agent 写动作提案（V2.4）。
 *
 * 安全模型：Agent 只能提案，后端裁判（确定性匹配）+ 用户确认后才执行。
 * LLM 只给标题类信息（planRef/stageTitle/taskTitle/newTitle），不给索引——
 * 阶段/任务由后端按标题匹配定位（匹配不到拒绝或追问，不猜）。
 *
 * V3.4 起跨域复用：尾部 articleId/articleTitle 为文章域字段（学习域动作不填，null）。
 * 字段随域增长互不相干——V3.5 域注册重构触发证据（收口文档见 agent-module-acceptance-checklist）。
 */
public record AgentWriteProposal(
        String actionType,
        String planRef,
        String stageTitle,
        String taskTitle,
        boolean done,
        String newTitle,
        String articleId,
        String articleTitle
) {

    /** 写动作类型（V2.4）：勾选 / 取消勾选已有任务（done 生效） */
    public static final String TYPE_UPDATE_TASK_DONE = "UPDATE_TASK_DONE";

    /** 写动作类型（V3.1）：向计划指定阶段追加用户点名的新任务（done 忽略） */
    public static final String TYPE_ADD_LEARNING_TASK = "ADD_LEARNING_TASK";

    /** 写动作类型（V3.3）：把已有任务重命名（taskTitle=旧名定位，newTitle=用户给的新名，done 忽略） */
    public static final String TYPE_UPDATE_LEARNING_TASK = "UPDATE_LEARNING_TASK";

    /**
     * 写动作类型（V3.4，文章域）：改自己文章标题。
     * articleId = 定位锚（必须来自页面上下文，后端查库验归属）；
     * articleTitle = 提案时刻的 DB 权威旧标题（并发防护锚，confirm stale 校验 + 条件更新都以它为 expected old value）；
     * newTitle = 用户原话的新标题；done 忽略。
     */
    public static final String TYPE_UPDATE_ARTICLE_TITLE = "UPDATE_ARTICLE_TITLE";

    /**
     * 写动作类型（V3.7，文章域可见性批次）：把当前自己的文章设为隐藏（前置 = 当前 PUBLISHED）。
     * articleId + articleTitle（DB 权威标题，确认卡展示用）；前置状态从动作方向推导，proposal 不加状态锚；
     * 执行由 updateArticleVisibility 的 WHERE status = expectedStatus 原子收口。
     */
    public static final String TYPE_HIDE_ARTICLE = "HIDE_ARTICLE";

    /** 写动作类型（V3.7，文章域可见性批次）：把当前自己的文章公开/取消隐藏（前置 = 当前 HIDDEN）。 */
    public static final String TYPE_PUBLISH_ARTICLE = "PUBLISH_ARTICLE";
}
