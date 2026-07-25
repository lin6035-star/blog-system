package com.hailin.blogsystem.entity.vo;

import com.hailin.blogsystem.entity.AiSessions;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiSessionVO {
    private String id;
    private String title;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;


    public static AiSessionVO from(AiSessions aiSessions) {
        AiSessionVO aiSessionVO = new AiSessionVO();
        aiSessionVO.setId(String.valueOf(aiSessions.getId()));
        aiSessionVO.setTitle(aiSessions.getTitle());
        aiSessionVO.setCreatedAt(aiSessions.getCreatedAt());
        aiSessionVO.setUpdatedAt(aiSessions.getUpdatedAt());
        return aiSessionVO;
    }
}
