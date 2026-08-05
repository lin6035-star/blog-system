package com.hailin.blogsystem.entity.vo;

import com.hailin.blogsystem.entity.AiUserMemories;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class AiUserMemoryVO {

    private String id;
    private String memoryType;
    private String memoryKey;
    private String content;
    private String source;
    private BigDecimal confidence;
    private Integer importance;
    private Integer enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static AiUserMemoryVO from(AiUserMemories memory) {
        AiUserMemoryVO vo = new AiUserMemoryVO();
        vo.setId(String.valueOf(memory.getId()));
        vo.setMemoryType(memory.getMemoryType());
        vo.setMemoryKey(memory.getMemoryKey());
        vo.setContent(memory.getContent());
        vo.setSource(memory.getSource());
        vo.setConfidence(memory.getConfidence());
        vo.setImportance(memory.getImportance());
        vo.setEnabled(memory.getEnabled());
        vo.setCreatedAt(memory.getCreatedAt());
        vo.setUpdatedAt(memory.getUpdatedAt());
        return vo;
    }
}