package com.hailin.blogsystem.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent Run 列表项（V2.2 inspection 层，只读，会话历史用）。
 */
@Data
public class AgentRunSummaryVO {
    private Long id;
    private String status;
    private String goal;
    private Integer usedSteps;
    private Integer maxSteps;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
