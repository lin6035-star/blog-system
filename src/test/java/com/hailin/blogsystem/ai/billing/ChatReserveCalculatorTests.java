package com.hailin.blogsystem.ai.billing;

import com.hailin.blogsystem.config.BlogAiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 聊天预扣公式。纯单测，不起 Spring 上下文。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §5.3
 *
 * <p>这里锁住的是「预扣来自可执行硬上限」这条主张的三个组成部分：
 * 输入按<b>真实长度</b>算（不是估分布）、输出受 maxTokens 约束、工具轮数放大。
 */
class ChatReserveCalculatorTests {

    private BlogAiProperties properties;
    private ChatReserveCalculator calculator;

    @BeforeEach
    void setUp() {
        properties = new BlogAiProperties();
        properties.setSystemPrompt("sys");            // 3 字符，每轮都要重发，必须计入

        BlogAiProperties.Billing billing = properties.getBilling();
        billing.setChatMaxTokens(1000);
        billing.setMaxToolRounds(2);
        billing.setCreditPer1kTokens(10);
        billing.setMaxReservePerBizType(Map.of("CHAT", 1_000_000L));

        calculator = new ChatReserveCalculator(properties);
    }

    @Test
    void estimateCountsSystemPromptAndInputThenScalesByToolRounds() {
        // 每轮 = 输入 100 + system 3 + 输出上限 1000 = 1103 token
        // 2 轮 = 2206 token → ceil(2206 * 10 / 1000) = 23 credit
        assertThat(calculator.estimate("x".repeat(100))).isEqualTo(23);
    }

    @Test
    void longerInputYieldsLargerReserve() {
        long shortReserve = calculator.estimate("x".repeat(100));
        long longReserve = calculator.estimate("x".repeat(10_000));

        // 输入是**实打实数出来的**，不是估的——所以更长的输入必然换来更大的预扣
        assertThat(longReserve).isGreaterThan(shortReserve);
    }

    @Test
    void estimateIsCappedByConfiguredMaxReserve() {
        properties.getBilling().setMaxReservePerBizType(Map.of("CHAT", 5L));

        // 压顶的目的是保证预扣永远碰不到 user_wallet 的 CHECK 下界
        assertThat(calculator.estimate("x".repeat(10_000))).isEqualTo(5);
    }

    @Test
    void estimateIsNeverZeroForNonEmptyInput() {
        // 整数除法会把小请求算成 0，那等于免费——所以必须向上取整
        properties.getBilling().setChatMaxTokens(0);
        properties.getBilling().setMaxToolRounds(1);

        assertThat(calculator.estimate("x")).isPositive();
    }

    @Test
    void nullPromptDoesNotBlowUp() {
        // 仍然至少要覆盖 system prompt + 输出上限
        assertThat(calculator.estimate(null)).isPositive();
    }
}
