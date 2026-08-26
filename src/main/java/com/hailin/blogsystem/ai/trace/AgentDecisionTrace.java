package com.hailin.blogsystem.ai.trace;

import com.hailin.blogsystem.entity.dto.AgentAction;
import com.hailin.blogsystem.entity.dto.AgentDecision;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.AiWorkflowType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 决策追踪：一次 Planner 裁决的快照。
 * 同时保存"模型建议"和"后端最终裁决"——看日志时能知道模型建议了什么、后端为什么接受或降级。
 */
@Data
@Builder
public class AgentDecisionTrace {

    private String requestId;
    private Long userId;
    private Long sessionId;

    // 只记录截断后的消息，避免日志过长
    private String messagePreview;

    // LLM 分类建议
    private String intent;
    private Double confidence;
    private String suggestedAction;
    private String suggestedWorkflowType;
    private String risk;

    // Planner 最终裁决
    private AgentAction action;
    private AiWorkflowType workflowType;
    private String toolName;
    private List<String> ruleHits;
    private String reason;

    private LocalDateTime createdAt;

    public static AgentDecisionTrace from(
            String requestId,
            Long userId,
            Long sessionId,
            String message,
            AiIntent intent,
            AgentDecision decision
    ) {
        return AgentDecisionTrace.builder()
                .requestId(requestId)
                .userId(userId)
                .sessionId(sessionId)
                .messagePreview(limit(message, 200))
                .intent(intent == null ? null : intent.getIntent())
                .confidence(intent == null ? null : intent.getConfidence())
                .suggestedAction(intent == null ? null : intent.getSuggestedAction())
                .suggestedWorkflowType(intent == null ? null : intent.getSuggestedWorkflowType())
                .risk(intent == null ? null : intent.getRisk())
                .action(decision == null ? null : decision.getAction())
                .workflowType(decision == null ? null : decision.getWorkflowType())
                .toolName(decision == null ? null : decision.getToolName())
                .ruleHits(decision == null || decision.getRuleHits() == null
                        ? List.of()
                        : List.copyOf(decision.getRuleHits()))
                .reason(decision == null ? null : decision.getReason())
                .createdAt(LocalDateTime.now())
                .build();
    }

    private static String limit(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength
                ? value
                : value.substring(0, maxLength);
    }
}
