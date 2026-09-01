package com.hailin.blogsystem.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Agent 步骤文案（V2.3）。
 *
 * 实时 AGENT_STEP 事件与历史 steps 补拉（AgentRunInspectionService）共用同一套文案，
 * 保证刷新前后思考面板展示一致。
 */
public final class AgentStepLabelSupport {

    private AgentStepLabelSupport() {
    }

    public static String actionLabel(String actionType) {
        return switch (actionType) {
            case "QUERY_LEARNING_DASHBOARD" -> "查询学习计划";
            case "QUERY_ARTICLE" -> "分析当前文章";
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
