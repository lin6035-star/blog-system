package com.hailin.blogsystem.ai.agent;

import com.hailin.blogsystem.entity.dto.PageContextDTO;

/**
 * 领域 Agent Runtime 统一接口（V2.5）。
 *
 * 学习域 / 文章域各自实现，由聊天链路（AiMessageServiceImpl 公共 SSE 管道）按意图分发调用。
 * pageContext 为后端权威页面上下文（可为 null），领域可忽略。
 */
public interface AgentRuntime {

    AgentRunResult run(
            Long userId,
            Long sessionId,
            String goal,
            PageContextDTO pageContext,
            AgentStepEmitter emitter
    );
}
