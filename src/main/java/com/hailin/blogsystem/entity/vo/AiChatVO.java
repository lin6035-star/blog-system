package com.hailin.blogsystem.entity.vo;

import lombok.Data;

@Data
public class AiChatVO {
    private AiSessionVO session;
    private AiMessageVO userMessage;
    private AiMessageVO assistantMessage;
}
