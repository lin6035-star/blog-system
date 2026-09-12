package com.hailin.blogsystem.entity;

import lombok.Getter;

@Getter
public enum AiChatEventType {
    DATA(1001, "数据事件"),
    STOP(1002, "停止事件"),
    PARAM(1003, "参数事件"),

    WORKFLOW_STEP(2001,"Workflow步骤事件"),
    WORKFLOW_STOP(2002, "Workflow结束事件"),
    WORKFLOW_ERROR(2003, "Workflow错误事件"),
    WORKFLOW_CONTENT_DELTA(2004, "Workflow内容增量事件"),

    AGENT_STEP(3001, "Agent思考步骤事件"),
    AGENT_PLAN(3002, "Agent计划事件"),

    /**
     * V4.5：聊天 / QA 轻量过程提示（瞬态 UI 状态，**不持久化**）。
     *
     * 发出时机必须在慢操作（QA 目标决议 / prompt 拼装 / 站内检索）**之前**——
     * 若在其后发，前端收到时检索早已结束，提示失去意义。
     *
     * 可能**早于 PARAM** 到达（PARAM 要等 userMessage 落库），所以前端
     * 按「当前流的 AI 占位消息索引」归并，不依赖任何消息 ID。
     */
    CHAT_STATUS(4001, "聊天状态事件");

    private final Integer value;
    private final String desc;

    AiChatEventType(Integer value, String desc) {
        this.value = value;
        this.desc = desc;
    }
}