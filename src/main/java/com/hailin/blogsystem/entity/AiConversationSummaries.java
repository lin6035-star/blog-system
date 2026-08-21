package com.hailin.blogsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_conversation_summaries")
public class AiConversationSummaries {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;
    private Long sessionId;
    private String summary;
    private String summaryJson;
    private Long coveredUntilMessageId;
    private Integer coveredMessageCount;
    private Integer version;
    private LocalDateTime lastCompressedAt;
    private Boolean compressing;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}