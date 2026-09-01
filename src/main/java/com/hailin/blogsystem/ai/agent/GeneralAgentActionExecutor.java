package com.hailin.blogsystem.ai.agent;

/**
 * 通用域只读动作执行器（V3 通用思考模式）。
 *
 * 每个动作返回一段 observation 文本（可裁剪后进上下文）。
 * 通用域动作白名单见 AgentStepActionType：QUERY_MEMORY / SEARCH_RAG + 终态。
 *
 * QUERY_MEMORY 是 V3 的核心增量（普通聊天现状不查记忆）；
 * SEARCH_RAG 只作证据补充，不是默认第一步（由 Decider prompt 约束）。
 *
 * 执行失败时抛异常，由 AbstractAgentRuntime 记录失败 step 并继续。
 */
public interface GeneralAgentActionExecutor extends AgentActionExecutor {
}
