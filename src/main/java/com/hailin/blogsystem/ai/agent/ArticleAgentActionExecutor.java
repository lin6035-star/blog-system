package com.hailin.blogsystem.ai.agent;

/**
 * 文章域只读动作执行器（V2.5）。
 *
 * 每个动作返回一段 observation 文本（可裁剪后进上下文）。
 * 文章域动作白名单见 AgentStepActionType。
 *
 * QUERY_ARTICLE 的 articleId 只当定位线索（优先后端页面上下文），
 * 最终以数据库校验归属为准：不存在 / 不属于当前用户 → 抛异常（FAILED step，循环继续）。
 *
 * 执行失败时抛异常，由 AbstractAgentRuntime 记录失败 step 并继续。
 */
public interface ArticleAgentActionExecutor extends AgentActionExecutor {
}
