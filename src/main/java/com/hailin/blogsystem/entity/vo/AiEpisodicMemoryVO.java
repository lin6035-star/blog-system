package com.hailin.blogsystem.entity.vo;

import com.hailin.blogsystem.entity.AiEpisodicMemories;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class AiEpisodicMemoryVO {

    private String id;
    private String projectKey;
    private String memoryType;
    private String title;
    private String content;
    private Integer importance;
    private BigDecimal confidence;
    private Long sessionId;
    private LocalDateTime occurredAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static AiEpisodicMemoryVO from(AiEpisodicMemories memory) {
        AiEpisodicMemoryVO vo = new AiEpisodicMemoryVO();
        vo.setId(String.valueOf(memory.getId()));
        vo.setProjectKey(memory.getProjectKey());
        vo.setMemoryType(memory.getMemoryType());
        vo.setTitle(memory.getTitle());
        vo.setContent(memory.getContent());
        vo.setImportance(memory.getImportance());
        vo.setConfidence(memory.getConfidence());
        vo.setSessionId(memory.getSessionId());
        vo.setOccurredAt(memory.getOccurredAt());
        vo.setCreatedAt(memory.getCreatedAt());
        vo.setUpdatedAt(memory.getUpdatedAt());
        return vo;
    }
}
