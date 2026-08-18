package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.memory.AiMemoryDecisionSanitizer;
import com.hailin.blogsystem.entity.dto.AiMemoryDecisionResult;
import com.hailin.blogsystem.entity.dto.MemoryRagContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiMemoryDecisionSanitizerTests {

    private final AiMemoryDecisionSanitizer sanitizer = new AiMemoryDecisionSanitizer();

    @Test
    void keepsValidMergeDecision() {
        AiMemoryDecisionResult decision = new AiMemoryDecisionResult();
        decision.setAction("MERGE");
        decision.setTargetMemoryId(18L);
        decision.setMergedContent("已完成 RAG、Memory，当前准备开发 Workflow。");
        decision.setReason("属于同一个项目推进阶段。");

        AiMemoryDecisionResult sanitized = sanitizer.sanitize(decision, candidateMemories());

        assertThat(sanitized.getAction()).isEqualTo("MERGE");
        assertThat(sanitized.getTargetMemoryId()).isEqualTo(18L);
        assertThat(sanitized.getMergedContent()).isEqualTo("已完成 RAG、Memory，当前准备开发 Workflow。");
    }

    @Test
    void fallsBackToCreateWhenTargetWasNotRetrieved() {
        AiMemoryDecisionResult decision = new AiMemoryDecisionResult();
        decision.setAction("UPDATE");
        decision.setTargetMemoryId(999L);
        decision.setReason("模型返回了未召回的目标记忆。");

        AiMemoryDecisionResult sanitized = sanitizer.sanitize(decision, candidateMemories());

        assertThat(sanitized.getAction()).isEqualTo("CREATE");
        assertThat(sanitized.getTargetMemoryId()).isNull();
    }

    @Test
    void fallsBackToUpdateWhenMergeHasNoMergedContent() {
        AiMemoryDecisionResult decision = new AiMemoryDecisionResult();
        decision.setAction("MERGE");
        decision.setTargetMemoryId(18L);

        AiMemoryDecisionResult sanitized = sanitizer.sanitize(decision, candidateMemories());

        assertThat(sanitized.getAction()).isEqualTo("UPDATE");
        assertThat(sanitized.getTargetMemoryId()).isEqualTo(18L);
    }

    private List<MemoryRagContext> candidateMemories() {
        return List.of(
                new MemoryRagContext(
                        18L,
                        1L,
                        "PROJECT_STATE",
                        "current_focus",
                        "已完成 RAG，准备开发 Memory。",
                        new BigDecimal("0.95"),
                        8
                )
        );
    }
}
