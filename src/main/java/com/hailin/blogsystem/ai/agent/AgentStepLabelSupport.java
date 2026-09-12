package com.hailin.blogsystem.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Agent 步骤文案（V2.3）。
 *
 * 实时 AGENT_STEP 事件与历史 steps 补拉（AgentRunInspectionService）共用同一套文案，
 * 保证刷新前后思考面板展示一致。
 */
public final class AgentStepLabelSupport {

    /** V4 规则级重复拦截：实时事件与历史补拉共用的展示文案。 */
    public static final String DUPLICATE_QUERY_SKIP_MESSAGE = "已跳过重复查询";
    /** 落库 errorMessage 前缀（历史补拉据此识别该场景）。 */
    public static final String DUPLICATE_QUERY_SKIP_PREFIX = "重复查询已跳过";

    private AgentStepLabelSupport() {
    }

    public static String actionLabel(String actionType) {
        return switch (actionType) {
            case "QUERY_LEARNING_DASHBOARD" -> "查询学习计划";
            // 2026-09-10 手测：原文案「分析当前文章」会让用户以为"读到了正文"，
            // 即便实际只拿到结构摘要/聚焦片段（甚至同一段反复读）也照说不误——
            // 改为中性描述"这次调用做了什么"，不暗示结果质量
            case "QUERY_ARTICLE" -> "读取文章";
            case "QUERY_MEMORY" -> "查询记忆";
            case "SEARCH_RAG" -> "检索站内知识";
            case "ASK_USER" -> "向你确认信息";
            case "FINAL_ANSWER" -> "生成最终回答";
            case "SUGGEST_WORKFLOW" -> "建议启动流程";
            case "SUGGEST_WRITE" -> "提案写动作";
            default -> actionType == null ? "决策" : actionType;
        };
    }

    public static String workflowLabel(String workflowType) {
        return switch (workflowType) {
            case "LEARNING_PLAN" -> "制定学习计划";
            case "LEARNING_PROGRESS" -> "调整学习进度";
            case "LEARNING_ASSIST" -> "难点攻坚";
            case "OPTIMIZE_ARTICLE" -> "文章优化";
            default -> workflowType == null ? "未知流程" : workflowType;
        };
    }

    /**
     * 步骤成功文案（与实时事件一致）：
     * SUGGEST_WORKFLOW 从 inputJson 解析 workflowType 带出中文流程名。
     */
    public static String completedMessage(String actionType, String inputJson, ObjectMapper objectMapper) {
        if ("SUGGEST_WORKFLOW".equals(actionType)) {
            return "建议启动「" + workflowLabel(parseWorkflowType(inputJson, objectMapper)) + "」流程";
        }
        return switch (actionType) {
            case "FINAL_ANSWER" -> "已生成最终回答";
            case "ASK_USER" -> "需要向你确认一个问题";
            case "DECISION" -> "决策完成";
            case "SUGGEST_WRITE" -> "已准备写动作提案";
            default -> "已完成" + actionLabel(actionType);
        };
    }

    public static String failedMessage(String actionType) {
        if ("SUGGEST_WORKFLOW".equals(actionType)) {
            return "建议被拒绝";
        }
        return actionLabel(actionType) + "失败";
    }

    /**
     * 带原因前缀的失败文案：V4 规则级重复拦截**不是失败，是系统主动跳过**——
     * 用「查询记忆失败」会让用户以为出了故障。
     *
     * 实时事件与历史补拉（AgentRunInspectionService）共用本方法，保证刷新前后一致；
     * 前缀不匹配时行为与 {@link #failedMessage(String)} 完全相同。
     */
    public static String failedMessage(String actionType, String errorMessage) {
        if (errorMessage != null && errorMessage.startsWith(DUPLICATE_QUERY_SKIP_PREFIX)) {
            return DUPLICATE_QUERY_SKIP_MESSAGE;
        }
        return failedMessage(actionType);
    }

    public static String skippedMessage(String actionType, String errorMessage) {
        if (errorMessage != null && errorMessage.startsWith(DUPLICATE_QUERY_SKIP_PREFIX)) {
            return DUPLICATE_QUERY_SKIP_MESSAGE;
        }
        return "已跳过" + actionLabel(actionType);
    }

    private static String parseWorkflowType(String inputJson, ObjectMapper objectMapper) {
        if (inputJson == null || inputJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(inputJson).path("workflowType").asText(null);
        } catch (Exception e) {
            return null;
        }
    }
}
