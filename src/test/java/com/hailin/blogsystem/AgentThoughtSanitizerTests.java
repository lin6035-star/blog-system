package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.agent.AgentThoughtSanitizer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * AgentThoughtSanitizer 分级闸门用例（V3.10 设计稿 §六 闸门用例表）。
 *
 * 分级：强信号（动作枚举 / json / 白名单 / 决策器 / 第 N 步）单命中即 null；
 * 弱词（API/工具/模型/参数/接口）单独出现放行，与机制动词（调用/返回/输出）同现才 null。
 */
class AgentThoughtSanitizerTests {

    // ==================== 强信号：单命中即 null ====================

    @Test
    void 动作枚举命中整句置null() {
        assertNull(AgentThoughtSanitizer.sanitize("我调用 QUERY_MEMORY 工具查询你的记忆"));
    }

    @Test
    void 裸枚举置null() {
        assertNull(AgentThoughtSanitizer.sanitize("FINAL_ANSWER"));
    }

    @Test
    void 小写枚举同样置null() {
        assertNull(AgentThoughtSanitizer.sanitize("先用 query_memory 查一下你的记忆"));
    }

    @Test
    void 白名单词置null() {
        assertNull(AgentThoughtSanitizer.sanitize("这个动作在白名单里，我直接执行"));
    }

    @Test
    void 第N步表述置null() {
        assertNull(AgentThoughtSanitizer.sanitize("第 1 步我先查一下你的计划"));
    }

    @Test
    void json词置null() {
        assertNull(AgentThoughtSanitizer.sanitize("我需要解析 json 才能继续"));
    }

    // ==================== 组合信号：弱词 + 机制动词同现才 null ====================

    @Test
    void 组合调用加API置null() {
        assertNull(AgentThoughtSanitizer.sanitize("这一步需要调用 API 才能继续"));
    }

    @Test
    void 组合调用加接口加返回置null() {
        assertNull(AgentThoughtSanitizer.sanitize("我调用了接口看返回结果"));
    }

    @Test
    void 组合模型加返回置null() {
        assertNull(AgentThoughtSanitizer.sanitize("模型返回了结果，我整理一下"));
    }

    // ==================== 弱词单独出现：放行（防误伤业务内容） ====================

    @Test
    void 工具单独出现放行() {
        assertEquals("我先找找讲 XX 工具怎么用的文章",
                AgentThoughtSanitizer.sanitize("我先找找讲 XX 工具怎么用的文章"));
    }

    @Test
    void api单独出现放行() {
        assertEquals("看看有没有 API 设计相关的文章",
                AgentThoughtSanitizer.sanitize("看看有没有 API 设计相关的文章"));
    }

    @Test
    void 模型单独出现放行() {
        assertEquals("我先看看模型对比的文章",
                AgentThoughtSanitizer.sanitize("我先看看模型对比的文章"));
    }

    @Test
    void 我让模型看看无机制动词放行() {
        assertEquals("我让模型看看你的计划里有没有相关安排",
                AgentThoughtSanitizer.sanitize("我让模型看看你的计划里有没有相关安排"));
    }

    @Test
    void 参数与接口业务用法放行() {
        assertEquals("先找一篇讲接口参数校验的文章",
                AgentThoughtSanitizer.sanitize("先找一篇讲接口参数校验的文章"));
    }

    // ==================== 干净句放行 ====================

    @Test
    void 干净动机句放行() {
        assertEquals("我先查查你的计划，看 Redis 学到哪了",
                AgentThoughtSanitizer.sanitize("我先查查你的计划，看 Redis 学到哪了"));
    }

    @Test
    void 观察收尾句放行() {
        assertEquals("看完观察，我觉得信息够了，直接回答",
                AgentThoughtSanitizer.sanitize("看完观察，我觉得信息够了，直接回答"));
    }

    // ==================== 近似词不误伤 ====================

    @Test
    void 决策树业务词放行() {
        assertEquals("先找找讲决策树的文章",
                AgentThoughtSanitizer.sanitize("先找找讲决策树的文章"));
    }

    @Test
    void 第几篇不误伤() {
        assertEquals("这文章第 2 篇讲得最细",
                AgentThoughtSanitizer.sanitize("这文章第 2 篇讲得最细"));
    }

    // ==================== 空输入与截断 ====================

    @Test
    void 空输入置null() {
        assertNull(AgentThoughtSanitizer.sanitize(null));
        assertNull(AgentThoughtSanitizer.sanitize(""));
        assertNull(AgentThoughtSanitizer.sanitize("   "));
    }

    @Test
    void 超长干净句截断至100字() {
        String longThought = "我".repeat(150);
        String result = AgentThoughtSanitizer.sanitize(longThought);
        assertEquals(100, result.length());
    }

    @Test
    void 首尾空白清理() {
        assertEquals("我先看看你的计划",
                AgentThoughtSanitizer.sanitize("  我先看看你的计划  "));
    }
}
