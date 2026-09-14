package com.hailin.blogsystem;

import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.service.impl.AiIntentClassifierImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「分类器从注入的计划列表里选序号 → 后端映射成权威 planId」的边界测试（V4.x）。
 *
 * 锁两条安全边界（被"优化"掉就是漏洞）：
 * 1）模型输出的 learningPlanId 字段一律不采信——这个字段只能由后端写；
 * 2）序号越界 / 缺失一律丢弃，绝不退而求其次挑一个最接近的（后端的"不猜"原则）。
 */
class AiIntentPlanSelectionTests {

    private LearningPlans plan(long id, String title) {
        LearningPlans plan = new LearningPlans();
        plan.setId(id);
        plan.setTitle(title);
        return plan;
    }

    private AiIntent intentWith(Integer index) {
        AiIntent intent = new AiIntent();
        intent.setIntent("LEARNING_ASSIST");
        intent.setLearningPlanIndex(index);
        return intent;
    }

    @Test
    void mapsIndexToAuthoritativePlanId() {
        AiIntent intent = intentWith(2);

        AiIntentClassifierImpl.resolveLearningPlanId(intent, List.of(
                plan(1001L, "C语言系统学习计划"),
                plan(1002L, "C++ 系统学习与工程化实践计划")));

        assertThat(intent.getLearningPlanId()).isEqualTo("1002");
    }

    @Test
    void dropsOutOfRangeIndex() {
        AiIntent intent = intentWith(3);

        AiIntentClassifierImpl.resolveLearningPlanId(intent, List.of(plan(1001L, "C语言系统学习计划")));

        assertThat(intent.getLearningPlanId()).isNull();
    }

    @Test
    void dropsNonPositiveIndex() {
        AiIntent zero = intentWith(0);
        AiIntent negative = intentWith(-1);

        AiIntentClassifierImpl.resolveLearningPlanId(zero, List.of(plan(1001L, "A")));
        AiIntentClassifierImpl.resolveLearningPlanId(negative, List.of(plan(1001L, "A")));

        assertThat(zero.getLearningPlanId()).isNull();
        assertThat(negative.getLearningPlanId()).isNull();
    }

    @Test
    void dropsNullIndex() {
        AiIntent intent = intentWith(null);

        AiIntentClassifierImpl.resolveLearningPlanId(intent, List.of(plan(1001L, "A")));

        assertThat(intent.getLearningPlanId()).isNull();
    }

    @Test
    void ignoresModelSuppliedPlanId() {
        // 模型幻觉出一个 learningPlanId：没有合法序号时一律清空，不能凭字段直通定位
        AiIntent hallucinated = intentWith(null);
        hallucinated.setLearningPlanId("999999");

        AiIntentClassifierImpl.resolveLearningPlanId(hallucinated, List.of(plan(1001L, "A")));

        assertThat(hallucinated.getLearningPlanId()).isNull();
    }

    @Test
    void emptyInjectedListYieldsNoPlanId() {
        // 游客 / 无 ACTIVE 计划：不注入列表，模型给什么序号都无意义
        AiIntent intent = intentWith(1);

        AiIntentClassifierImpl.resolveLearningPlanId(intent, List.of());

        assertThat(intent.getLearningPlanId()).isNull();
    }
}
