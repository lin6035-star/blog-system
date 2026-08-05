package com.hailin.blogsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("ai_user_memory_candidates")
public class AiUserMemoryCandidate {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;
    private Long sessionId;
    private Long messageId;

    private String memoryType;
    private String memoryKey;
    private String content;

    private String candidateAction;
    private String reason;
    private String decisionReason;
    private String mergedContent;
    private String source;

    private BigDecimal confidence;
    private Integer importance;

    private String status;
    private Long targetMemoryId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime decidedAt;
}
