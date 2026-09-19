package com.hailin.blogsystem.ai.task;

/**
 * AI 长任务类型。用于观测（{@code [AI-TASK]} 日志）与拒绝文案区分。
 *
 * <p>只覆盖**同步阻塞的 Agent / Workflow 编排任务**——普通聊天正文走响应式模型流，
 * 由既有的 {@code chat} 固定窗口限流与钱包计费保护，不在这里。
 */
public enum AiTaskType {

    /** Agent Runtime 循环（含文章域 / 学习域）。 */
    AGENT,

    /** 从聊天意图创建 Workflow（文章创作 / 文章优化 / 学习规划 / 学习调整 / 难点攻坚）。 */
    WORKFLOW_CREATE,

    /** Workflow 的 approve 推进。 */
    WORKFLOW_APPROVE,

    /** Workflow 的 reject。 */
    WORKFLOW_REJECT,

    /** Workflow 的 retry。 */
    WORKFLOW_RETRY
}
