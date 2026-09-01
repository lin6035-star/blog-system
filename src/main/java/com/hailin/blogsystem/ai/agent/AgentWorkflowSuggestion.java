package com.hailin.blogsystem.ai.agent;

/**
 * Agent 待确认的 Workflow 建议（V2.1）。
 *
 * - workflowType：只允许各领域 allowlist（学习类 / 文章类 OPTIMIZE_ARTICLE），
 *   由后端 allowlist 校验（Runtime 裁判 + confirm 二次校验）
 * - reason：给用户看的建议原因（中文）
 * - initialMessage：启动 Workflow 时的用户原始诉求（默认取 run goal）
 * - risk：信息性字段（V2.1 默认 MEDIUM，不参与裁决）
 * - articleId：V2.5 文章侧建议的目标文章 ID（仅 OPTIMIZE_ARTICLE 建议非空，学习类为 null）
 *
 * 序列化进 ai_agent_runs.context_json，confirm / cancel 时读取。
 */
public record AgentWorkflowSuggestion(
        String workflowType,
        String reason,
        String initialMessage,
        String risk,
        String articleId
) {
}
