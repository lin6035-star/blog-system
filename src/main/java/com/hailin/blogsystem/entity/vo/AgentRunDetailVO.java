package com.hailin.blogsystem.entity.vo;

import com.hailin.blogsystem.ai.agent.AgentWorkflowSuggestion;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent Run 详情（V2.2 inspection 层，只读）。
 *
 * 安全摘要：不含完整 prompt / 内部脏上下文；
 * pendingWorkflowSuggestion 仅 WAITING_WORKFLOW_CONFIRM 状态非空。
 */
@Data
public class AgentRunDetailVO {
    private Long id;
    private Long sessionId;
    private String goal;
    private String status;
    private Integer currentStep;
    private Integer usedSteps;
    private Integer maxSteps;
    private String finalAnswer;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private AgentWorkflowSuggestion pendingWorkflowSuggestion;
}
