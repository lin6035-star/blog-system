package com.hailin.blogsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("ai_episodic_memories")
public class AiEpisodicMemories {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;
    private Long sessionId;
    private String projectKey; //项目隔离键

    private String memoryType; //情景记忆类型：DECISION / EVENT / MILESTONE / PLAN'
    private String title;  //短标题，方便列表和调试
    private String content;  //压缩后的历史事件描述，必须保留原因

    private Integer importance;  //重要性1-10
    private BigDecimal confidence;  //可信度0-1

    private String sourceMessageIds;  //来源消息ID数组
    private String contentHash;  //内容哈希，用于硬去重

    private LocalDateTime occurredAt;  //事件发生时间，默认取窗口最后一条消息时间
    private LocalDateTime lastRetrievedAt;  //最近一次被召回时间
    private Integer retrievalCount;  //找回次数

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
