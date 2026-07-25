package com.hailin.blogsystem.entity.vo;

import com.hailin.blogsystem.entity.AiMessages;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiMessageVO {
    private String id;
    private String sessionId;
    private String role;  //user or assistant
    private String content;
    private String pageContext;
    private LocalDateTime createdAt;


    public static AiMessageVO from(AiMessages aiMessages) {
        AiMessageVO aiMessageVO = new AiMessageVO();
        aiMessageVO.setId(String.valueOf(aiMessages.getId()));
        aiMessageVO.setSessionId(String.valueOf(aiMessages.getSessionId()));
        aiMessageVO.setRole(aiMessages.getRole());
        aiMessageVO.setContent(aiMessages.getContent());
        aiMessageVO.setPageContext(aiMessages.getPageContext());
        aiMessageVO.setCreatedAt(aiMessages.getCreatedAt());

        return aiMessageVO;
    }
}
