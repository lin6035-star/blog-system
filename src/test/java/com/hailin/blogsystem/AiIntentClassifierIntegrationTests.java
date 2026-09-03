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

    @Test
    void classifiesExplicitAddTaskRequestAsLearningAgentNotLearningAssist() {
        // V3.1：用户给出任务名（受控写素材）→ LEARNING_AGENT（走 SUGGEST_WRITE 受控写），
        // 不是 LEARNING_ASSIST workflow（AI 拆解语义）——两意图分界靠真实模型锁定
        AiIntent intent = aiIntentClassifier.classify(
                "给 Redis 计划第二阶段加一个缓存雪崩防护任务",
                null
        );

        assertThat(intent.getIntent()).isEqualTo("LEARNING_AGENT");
    }

    @Test
    void classifiesProgressRequestWithPlanNamedAsLearningProgress() {
        // 回归：用户点名计划 + 阶段的调整诉求 → LEARNING_PROGRESS + learningPlanRef 摘录
        // （摘录丢失 → 入口拿整句匹配多计划会失手 → 用户被迫先选计划）
        AiIntent intent = aiIntentClassifier.classify(
                "把 C++ 计划的第二阶段压缩一下",
                null
        );

        assertThat(intent.getIntent()).isEqualTo("LEARNING_PROGRESS");
        assertThat(intent.getLearningPlanRef()).isNotBlank();
    }

    @Test
    void classifiesAssistRequestWithPlanNamedAsLearningAssist() {
        // 回归：用户点名计划 + 阶段的攻坚拆解诉求 → LEARNING_ASSIST + learningPlanRef 摘录
        AiIntent intent = aiIntentClassifier.classify(
                "C++ 计划的第三阶段太难了，你能帮我拆解一下吗",
                null
        );

        assertThat(intent.getIntent()).isEqualTo("LEARNING_ASSIST");
        assertThat(intent.getLearningPlanRef()).isNotBlank();
    }

    @Test
    void classifiesNamedAddTaskWithPlanAndStageAsLearningAgent() {
        // 回归（用户实测场景 3）：点名计划 + 阶段 + 具体任务名 → LEARNING_AGENT 受控写，
        // 不能落普通聊天工具链（无写工具 → LLM 只能回复「无法直接修改」）
        AiIntent intent = aiIntentClassifier.classify(
                "C++ 计划的阶段1，帮我加一下这个小任务：练习引用与指针在函数传参中的区别",
                null
        );

        assertThat(intent.getIntent()).isEqualTo("LEARNING_AGENT");
        assertThat(intent.getSuggestedAction()).isEqualTo("AGENT");
    }

    @Test
    void classifiesMultilineNamedAddTaskRequestAsLearningAgent() {
        // 回归（用户实测翻车场景）：多行消息 + 长任务名曾导致模型把规则文字混进 JSON → 解析失败降级普通聊天
        AiIntent intent = aiIntentClassifier.classify(
                "C++计划的阶段1,帮我加一下这个小任务\n练习引用与指针在函数传参中的区别",
                null
        );

        assertThat(intent.getIntent()).isEqualTo("LEARNING_AGENT");
        assertThat(intent.getSuggestedAction()).isEqualTo("AGENT");
    }
}
