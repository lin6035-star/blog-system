package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.agent.AgentPlanValidator;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan Preview 计划校验闸门测试（V3.13）。
 * 零修补：任一规则不过 → 整条计划丢弃（null），不挑拣、不截断、不改写。
 */
class AgentPlanValidatorTests {

    @Test
    void validatesAndTrimsNormalPlan() {
        List<String> plan = AgentPlanValidator.validate(List.of(
                " 先看整体结构 ", "再检查缓存击穿那一节", "结合你的写作偏好给建议"));

        assertThat(plan).containsExactly(
                "先看整体结构", "再检查缓存击穿那一节", "结合你的写作偏好给建议");
    }

    @Test
    void rejectsNullInput() {
        assertThat(AgentPlanValidator.validate(null)).isNull();
    }

    @Test
    void rejectsWhenTooFewItems() {
        // 1 项不是多目标
        assertThat(AgentPlanValidator.validate(List.of("先看整体结构"))).isNull();
    }

    @Test
    void rejectsWhenTooManyItems() {
        assertThat(AgentPlanValidator.validate(List.of("一", "二", "三", "四", "五"))).isNull();
    }

    @Test
    void acceptsBoundaryItemCounts() {
        assertThat(AgentPlanValidator.validate(List.of("一", "二"))).hasSize(2);
        assertThat(AgentPlanValidator.validate(List.of("一", "二", "三", "四"))).hasSize(4);
    }

    @Test
    void rejectsBlankOrNullItem() {
        assertThat(AgentPlanValidator.validate(Arrays.asList("先看结构", "   "))).isNull();
        assertThat(AgentPlanValidator.validate(Arrays.asList("先看结构", null))).isNull();
    }

    @Test
    void rejectsDuplicateItemsCaseInsensitively() {
        assertThat(AgentPlanValidator.validate(List.of("看结构", "看结构"))).isNull();
        // 规范化（trim + 统一小写）后比对
        assertThat(AgentPlanValidator.validate(List.of("Redis 定位", "redis 定位"))).isNull();
    }

    @Test
    void rejectsOverlongItem() {
        String longItem = "看".repeat(61);
        assertThat(AgentPlanValidator.validate(List.of("先看结构", longItem))).isNull();
    }

    @Test
    void acceptsItemAtLengthLimit() {
        String atLimit = "看".repeat(60);
        assertThat(AgentPlanValidator.validate(List.of("先看结构", atLimit))).hasSize(2);
    }

    @Test
    void rejectsWhenTotalLengthOverLimit() {
        // 单项 55 字合法，但 4 项累计 220 > 200
        String item = "看".repeat(55);
        assertThat(AgentPlanValidator.validate(List.of(item, item + "a", item + "b", item + "c")))
                .isNull();
    }

    @Test
    void rejectsMechanismWords() {
        assertThat(AgentPlanValidator.validate(List.of("先 QUERY_ARTICLE 看结构", "再给建议"))).isNull();
        assertThat(AgentPlanValidator.validate(List.of("先看结构", "用 SEARCH_RAG 找"))).isNull();
        assertThat(AgentPlanValidator.validate(List.of("先看结构", "输出 JSON 计划"))).isNull();
        assertThat(AgentPlanValidator.validate(List.of("先看结构", "遵守 prompt 约束"))).isNull();
        assertThat(AgentPlanValidator.validate(List.of("先看结构", "命中白名单"))).isNull();
        assertThat(AgentPlanValidator.validate(List.of("先看结构", "第 2 步看记忆"))).isNull();
    }

    @Test
    void allowsBusinessWordsThatAreNotMechanism() {
        // 站内是技术博客——「API / 工具 / 模型 / 参数 / 接口」可能是文章主题，单独出现放行
        assertThat(AgentPlanValidator.validate(List.of(
                "先看 API 设计那一节", "再结合模型对比给建议"))).hasSize(2);
        assertThat(AgentPlanValidator.validate(List.of(
                "先看整体结构", "整理文中提到的工具用法"))).hasSize(2);
    }
}
