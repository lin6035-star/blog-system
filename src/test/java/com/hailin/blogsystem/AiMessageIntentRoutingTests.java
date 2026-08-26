package com.hailin.blogsystem;

import com.hailin.blogsystem.entity.dto.AiIntent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AiMessageServiceImpl 语义意图字段测试。
 *
 * 语义路由已经迁移到 AgentPlannerSupport，
 * classifyWithFallback 兼容入口已删除，
 * 这里只保留纯 DTO 级别的字段保持验证。
 */
class AiMessageIntentRoutingTests {

    @Test
    void learningIntentKeepsSemanticReferences() {
        AiIntent intent = new AiIntent();
        intent.setIntent("LEARNING_ASSIST");
        intent.setConfidence(0.94);
        intent.setLearningPlanRef("微服务");
        intent.setLearningStageRef("阶段二");

        assertThat(intent.getIntent())
                .isEqualTo("LEARNING_ASSIST");

        assertThat(intent.getConfidence())
                .isEqualTo(0.94);

        assertThat(intent.getLearningPlanRef())
                .isEqualTo("微服务");

        assertThat(intent.getLearningStageRef())
                .isEqualTo("阶段二");
    }
}
