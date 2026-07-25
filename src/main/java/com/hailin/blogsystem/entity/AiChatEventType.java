package com.hailin.blogsystem.entity;

import lombok.Getter;

@Getter
public enum AiChatEventType {
    DATA(1001, "数据事件"),
    STOP(1002, "停止事件"),
    PARAM(1003, "参数事件");

    private final Integer value;
    private final String desc;

    AiChatEventType(Integer value, String desc) {
        this.value = value;
        this.desc = desc;
    }
}