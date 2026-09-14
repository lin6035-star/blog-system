package com.hailin.blogsystem.entity.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Agent 最终裁决结果。
 *
 * LLM 只负责输出 AiIntent 建议，
 * AgentPlannerSupport 负责生成 AgentDecision。
 */
@Data
@Builder
public class AgentDecision {

    /**
     * 最终动作：
     * CHAT / TOOL / WORKFLOW / CTA
     */
    private AgentAction action;

    /**
     * 最终确认后的意图。
     * 例如：
     * GENERAL_CHAT
     * ARTICLE_SEARCH
     * ARTICLE_DETAIL_QA
     * CREATE_ARTICLE_WORKFLOW
     * OPTIMIZE_ARTICLE_WORKFLOW
     */
    private String intent;

    /**
     * Workflow 类型。
     * 只有 action=WORKFLOW 时才有值。
     */
    private AiWorkflowType workflowType;

    /**
     * Tool 名称。
     * 只有 action=TOOL 时才有值。
     */
    private String toolName;

    /**
     * RAG 检索模式。
     *
     * 当前允许：
     * NONE
     * ARTICLE_SEARCH
     * CURRENT_ARTICLE
     */
    private String retrievalMode;

    /**
     * 前端动作类型。
     *
     * 当前先兼容旧入口，后续统一导航、编辑器和文章动作时使用。
     *
     * 例如：
     * navigate
     * saveDraft
     * publish
     * likeArticle
     */
    private String clientActionType;

    /**
     * 后端命中的确定性规则。
     * 用于解释为什么允许或拒绝某个动作。
     */
    private List<String> ruleHits;

    /**
     * 最终裁决原因。
     *
     * 开发者诊断用（进 AgentDecisionTrace / 评测门断言），**不直接进用户文案**——
     * 面向用户的解释由 ctaKind 分类后渲染（V4.x）。
     */
    private String reason;

    /**
     * CTA 的面向用户原因分类（V4.x）。
     * 只有 action=CTA 时有值；缺省视为 SYSTEM_UNCERTAIN。
     */
    private CtaKind ctaKind;

    public boolean isTool(String name) {
        return action == AgentAction.TOOL
                && name != null
                && name.equals(toolName);
    }

    public boolean isWorkflow(AiWorkflowType expectedType) {
        return action == AgentAction.WORKFLOW
                && expectedType != null
                && expectedType == workflowType;
    }

    public boolean isIntent(String expectedIntent) {
        return expectedIntent != null
                && expectedIntent.equals(intent);
    }

    public boolean usesRetrieval(String expectedMode) {
        return expectedMode != null
                && expectedMode.equals(retrievalMode);
    }
}
