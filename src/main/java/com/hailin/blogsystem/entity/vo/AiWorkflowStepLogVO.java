package com.hailin.blogsystem.entity.vo;

import com.hailin.blogsystem.entity.AiWorkflowStepLog;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiWorkflowStepLogVO {

    private String id;
    private String workflowRunId;
    private String logType;
    private Integer stepOrder;
    private String step;
    private String status;
    private Integer retryCount;
    private String inputSummary;
    private String outputSummary;
    private String errorMessage;
    private String metadataJson;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Long durationMs;
    private Integer inputTokens;
    private Integer outputTokens;
    private LocalDateTime createdAt;

    public static AiWorkflowStepLogVO from(AiWorkflowStepLog log) {
        AiWorkflowStepLogVO vo = new AiWorkflowStepLogVO();
        vo.setId(String.valueOf(log.getId()));
        vo.setWorkflowRunId(String.valueOf(log.getWorkflowRunId()));
        vo.setLogType(log.getLogType());
        vo.setStepOrder(log.getStepOrder());
        vo.setStep(log.getStep());
        vo.setStatus(log.getStatus());
        vo.setRetryCount(log.getRetryCount());
        vo.setInputSummary(log.getInputSummary());
        vo.setOutputSummary(log.getOutputSummary());
        vo.setErrorMessage(log.getErrorMessage());
        vo.setMetadataJson(log.getMetadataJson());
        vo.setStartedAt(log.getStartedAt());
        vo.setEndedAt(log.getEndedAt());
        vo.setDurationMs(log.getDurationMs());
        vo.setInputTokens(log.getInputTokens());
        vo.setOutputTokens(log.getOutputTokens());
        vo.setCreatedAt(log.getCreatedAt());
        return vo;
    }
}
