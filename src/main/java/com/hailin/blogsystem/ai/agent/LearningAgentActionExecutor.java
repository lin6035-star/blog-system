package com.hailin.blogsystem.ai.agent;

/**
 * 学习域只读动作执行器（V2.5 起继承公共接口 AgentActionExecutor）。
 *
 * 每个动作返回一段 observation 文本（可裁剪后进上下文）。
 * 学习域动作白名单见 AgentStepActionType。
 *
 * 执行失败时抛异常，由 AbstractAgentRuntime 记录失败 step 并继续。
 */
public interface LearningAgentActionExecutor extends AgentActionExecutor {
}
