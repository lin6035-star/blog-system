package com.hailin.blogsystem;

import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.service.AiIntentClassifier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest
@EnabledIfSystemProperty(named = "runRealLlm", matches = "true")
class AiIntentClassifierIntegrationTests {

    @Autowired
    private AiIntentClassifier aiIntentClassifier;

    @Test
    void classifiesLearningDifficultyAsLearningAssist() {
        AiIntent intent = aiIntentClassifier.classify(
                "我感觉微服务计划中的阶段二挺难的，帮我拆成几个更小的任务",
                null
        );

        assertThat(intent.getIntent()).isEqualTo("LEARNING_ASSIST");
        assertThat(intent.getLearningPlanRef()).contains("微服务");
        assertThat(intent.getLearningStageRef()).contains("阶段二");
        assertThat(intent.getConfidence()).isGreaterThanOrEqualTo(0.55);
    }

    @Test
    void classifiesStateDependentChatAsNeedsThinking() {
        // V3.0：状态依赖句（记忆/上下文信号明确）→ GENERAL_CHAT + needsThinking=true
        AiIntent intent = aiIntentClassifier.classify(
                "结合我最近的情况，给个建议",
                null
        );

        assertThat(intent.getIntent()).isEqualTo("GENERAL_CHAT");
        assertThat(intent.getNeedsThinking()).isTrue();
        assertThat(intent.getNeedsThinkingReason()).isNotBlank();
    }

    @Test
    void classifiesPureConceptChatAsNoThinking() {
        // V3.0：纯概念问答 → GENERAL_CHAT + needsThinking=false（默认 false 语义）
        AiIntent intent = aiIntentClassifier.classify(
                "什么是缓存穿透",
                null
        );

        assertThat(intent.getIntent()).isEqualTo("GENERAL_CHAT");
        assertThat(intent.getNeedsThinking()).isFalse();
    }
}
