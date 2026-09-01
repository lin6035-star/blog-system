package com.hailin.blogsystem.entity.dto;

/**
 * Agent Runtime 步骤动作类型。
 *
 * 与顶层 AgentAction（CHAT / TOOL / WORKFLOW / CTA / AGENT）不同，
 * 这是 Agent Loop 内部每一步可执行的动作白名单。
 *
 * V1 只读白名单：
 * - QUERY_LEARNING_DASHBOARD：查询学习计划总览 / 进度
 * - QUERY_MEMORY：按需查询用户长期记忆（不自动注入）
 * - SEARCH_RAG：检索站内文章知识
 * - ASK_USER：向用户提问（V1 终态动作）
 * - FINAL_ANSWER：产出最终回答（V1 终态动作）
 *
 * V2.1 新增：
 * - SUGGEST_WORKFLOW：建议启动学习类 Workflow（终态，后端裁判 + 用户确认后才会真正启动）
 *
 * V2.4 新增：
 * - SUGGEST_WRITE：写动作提案（终态，如勾选任务完成；后端裁判 + 用户确认后才会执行）
 *
 * V2.5 新增（文章域 Agent）：
 * - QUERY_ARTICLE：查询当前文章并做结构分析（只读，归属校验由执行器完成）
 *
 * 直接写动作（UPDATE_PROGRESS / CREATE_PLAN / START_WORKFLOW / SAVE_ARTICLE 等）
 * 一直明确禁止——Agent 只能提案，不能直接写。
 */
public enum AgentStepActionType {
    QUERY_LEARNING_DASHBOARD,
    QUERY_MEMORY,
    SEARCH_RAG,
    QUERY_ARTICLE,
    ASK_USER,
    FINAL_ANSWER,
    SUGGEST_WORKFLOW,
    SUGGEST_WRITE
}
