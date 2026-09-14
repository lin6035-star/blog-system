package com.hailin.blogsystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.agent.AgentStepDecision;
import com.hailin.blogsystem.ai.AiJudgeModelSupport;
import com.hailin.blogsystem.ai.agent.LlmAgentStepDecider;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LLM 决策器测试。
 * mock ChatClient 返回固定 JSON，验证解析 / 修复一次 / 白名单外拒绝 / 总结降级。
 */
class LlmAgentStepDeciderTests {

    private ChatClient.Builder builder;
    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callSpec;
    private LlmAgentStepDecider decider;

    @BeforeEach
    void setUp() {
        builder = mock(ChatClient.Builder.class);
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);

        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);

        decider = new LlmAgentStepDecider(builder, new ObjectMapper(), new AiJudgeModelSupport(""));
    }

    @Test
    void parsesValidDashboardDecision() {
        when(callSpec.content()).thenReturn(
                "{\"actionType\":\"QUERY_LEARNING_DASHBOARD\",\"input\":{\"planRef\":\"Redis\"}}"
        );

        AgentStepDecision decision = decider.decide("今天学什么", "", 1, 5);

        assertThat(decision).isNotNull();
        assertThat(decision.actionType()).isEqualTo(AgentStepActionType.QUERY_LEARNING_DASHBOARD);
        assertThat(decision.input()).containsEntry("planRef", "Redis");
    }

    @Test
    void parsesFinalAnswerDecisionWithAnswerText() {
        when(callSpec.content()).thenReturn(
                "{\"actionType\":\"FINAL_ANSWER\",\"input\":{\"answer\":\"今天建议学 Redis 持久化\"}}"
        );

        AgentStepDecision decision = decider.decide("今天学什么", "观察1", 2, 5);

        assertThat(decision.actionType()).isEqualTo(AgentStepActionType.FINAL_ANSWER);
        assertThat(decision.input()).containsEntry("answer", "今天建议学 Redis 持久化");
    }

    @Test
    void stripsMarkdownFenceBeforeParsing() {
        when(callSpec.content()).thenReturn("```json\n{\"actionType\":\"ASK_USER\",\"input\":{\"question\":\"选哪个计划？\"}}\n```");

        AgentStepDecision decision = decider.decide("今天学什么", "", 1, 5);

        assertThat(decision.actionType()).isEqualTo(AgentStepActionType.ASK_USER);
        assertThat(decision.input()).containsEntry("question", "选哪个计划？");
    }

    @Test
    void repairsOnceWhenFirstOutputIsInvalidJson() {
        when(callSpec.content())
                .thenReturn("我不是 JSON")
                .thenReturn("{\"actionType\":\"QUERY_MEMORY\",\"input\":{\"question\":\"学习偏好\"}}");

        AgentStepDecision decision = decider.decide("今天学什么", "", 1, 5);

        assertThat(decision).isNotNull();
        assertThat(decision.actionType()).isEqualTo(AgentStepActionType.QUERY_MEMORY);
    }

    @Test
    void returnsNullWhenBothAttemptsFail() {
        when(callSpec.content())
                .thenReturn("还是不是 JSON")
                .thenReturn("依旧不是 JSON");

        AgentStepDecision decision = decider.decide("今天学什么", "", 1, 5);

        assertThat(decision).isNull();
    }

    @Test
    void rejectsActionOutsideWhitelist() {
        when(callSpec.content()).thenReturn("{\"actionType\":\"DELETE_EVERYTHING\",\"input\":{}}");

        AgentStepDecision decision = decider.decide("今天学什么", "", 1, 5);

        assertThat(decision).isNull();
    }

    @Test
    void returnsNullWhenLlmCallThrows() {
        when(callSpec.content()).thenThrow(new RuntimeException("API 调用失败"));

        AgentStepDecision decision = decider.decide("今天学什么", "", 1, 5);

        assertThat(decision).isNull();
    }

    @Test
    void summarizeReturnsText() {
        when(callSpec.content()).thenReturn("建议继续学 Redis 持久化，下一阶段是缓存击穿。");

        String summary = decider.summarize("今天学什么", "观察1\n观察2");

        assertThat(summary).contains("缓存击穿");
    }

    @Test
    void summarizeReturnsNullOnFailure() {
        when(callSpec.content()).thenThrow(new RuntimeException("总结失败"));

        String summary = decider.summarize("今天学什么", "观察1");

        assertThat(summary).isNull();
    }
}
