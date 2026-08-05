package com.hailin.blogsystem.entity.vo;

import com.hailin.blogsystem.entity.AiUserMemoryCandidate;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class AiUserMemoryCandidateVO {
    private String id;
    private String memoryType;
    private String memoryKey;
    private String content;
    private String candidateAction;
    private String reason;
    private String decisionReason;
    private String mergedContent;
    private BigDecimal confidence;
    private Integer importance;
    private String status;
    private LocalDateTime createdAt;
    private String targetMemoryId;

    public static AiUserMemoryCandidateVO from(AiUserMemoryCandidate candidate) {
        AiUserMemoryCandidateVO vo = new AiUserMemoryCandidateVO();
        vo.setId(String.valueOf(candidate.getId()));
        vo.setMemoryType(candidate.getMemoryType());
        vo.setMemoryKey(candidate.getMemoryKey());
        vo.setContent(candidate.getContent());
        vo.setCandidateAction(candidate.getCandidateAction());
        vo.setReason(candidate.getReason());
        vo.setDecisionReason(candidate.getDecisionReason());
        vo.setMergedContent(candidate.getMergedContent());
        vo.setConfidence(candidate.getConfidence());
        vo.setImportance(candidate.getImportance());
        vo.setStatus(candidate.getStatus());
        vo.setCreatedAt(candidate.getCreatedAt());
        if (candidate.getTargetMemoryId() != null) {
            vo.setTargetMemoryId(String.valueOf(candidate.getTargetMemoryId()));
        }
        return vo;
    }
}
