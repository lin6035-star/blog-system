package com.hailin.blogsystem.entity.dto;

import lombok.Data;

@Data
public class AiChatDTO {
    private String sessionId;
    private String message;
    private PageContextDTO pageContext;
}
