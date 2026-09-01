package com.hailin.blogsystem.ai.agent;

import com.hailin.blogsystem.entity.dto.AgentStepActionType;

import java.util.Map;

/**
 * Agent 每一步的决策结果。
 *
 * 由决策器（V1 为 LLM，测试时为 mock）产生：
 * - actionType：白名单内的动作
 * - input：动作参数（如 QUERY_LEARNING_DASHBOARD 的关键词、SEARCH_RAG 的检索词）
 */
public record AgentStepDecision(
        AgentStepActionType actionType,
        Map<String, Object> input
) {

    public static AgentStepDecision of(AgentStepActionType actionType) {
        return new AgentStepDecision(actionType, Map.of());
    }

    public AgentStepDecision withInput(Map<String, Object> newInput) {
        return new AgentStepDecision(actionType, newInput);
    }
}
