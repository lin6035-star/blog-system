package com.hailin.blogsystem.ai.agent;

import com.hailin.blogsystem.entity.dto.PageContextDTO;

/**
 * Agent 只读动作执行器（V2.5 抽取公共接口）。
 *
 * 每个动作返回一段 observation 文本（可裁剪后进上下文）。
 * 白名单见 AgentStepActionType。
 *
 * pageContext：后端权威页面上下文（V2.5 起传入），
 * 领域执行器可据此定位当前文章等线索，但最终仍以数据库校验为准。
 *
 * 执行失败时抛异常，由 AbstractAgentRuntime 记录失败 step 并继续。
 */
public interface AgentActionExecutor {

    String execute(AgentStepDecision decision, Long userId, PageContextDTO pageContext);
}
