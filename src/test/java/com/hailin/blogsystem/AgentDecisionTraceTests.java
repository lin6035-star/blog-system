package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.trace.AgentDecisionTrace;
import com.hailin.blogsystem.entity.dto.AgentAction;
import com.hailin.blogsystem.entity.dto.AgentDecision;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.AiWorkflowType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentDecisionTraceTests {

    @Test
    void traceContainsClassifierSuggestionAndPlannerDecision() {
        AiIntent intent = new AiIntent();
        intent.setIntent("LEARNING_PLAN");
        intent.setConfidence(0.92);
        intent.setSuggestedAction("WORKFLOW");
        intent.setSuggestedWorkflowType("LEARNING_PLAN");
        intent.setRisk("LOW");

        AgentDecision decision = AgentDecision.builder()
                .action(AgentAction.WORKFLOW)
                .workflowType(AiWorkflowType.LEARNING_PLAN)
                .ruleHits(List.of("learning_plan_rule"))
                .reason("双签命中")
                .build();

        AgentDecisionTrace trace = AgentDecisionTrace.from(
                "req-001",
                100L,
                200L,
                "我想学习 Redis",
                intent,
                decision
        );

        assertThat(trace.getRequestId()).isEqualTo("req-001");
        assertThat(trace.getIntent()).isEqualTo("LEARNING_PLAN");
        assertThat(trace.getSuggestedAction()).isEqualTo("WORKFLOW");
        assertThat(trace.getAction()).isEqualTo(AgentAction.WORKFLOW);
        assertThat(trace.getWorkflowType()).isEqualTo(AiWorkflowType.LEARNING_PLAN);
        assertThat(trace.getRuleHits()).containsExactly("learning_plan_rule");
    }

    @Test
    void messagePreviewIsCapped() {
        String longMessage = "a".repeat(300);

        AgentDecisionTrace trace = AgentDecisionTrace.from(
                "req-002",
                100L,
                200L,
                longMessage,
                null,
                null
        );

        assertThat(trace.getMessagePreview()).hasSize(200);
    }
}
