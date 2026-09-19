package com.hailin.blogsystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.ai.agent.AgentStepDecision;
import com.hailin.blogsystem.ai.AiJudgeModelSupport;
import com.hailin.blogsystem.ai.agent.LlmAgentStepDecider;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LLM 决策器测试。
 * mock ChatClient 返回固定 JSON，验证解析 / 修复一次 / 白名单外拒绝 / 总结降级 / token 累计。
 *
 * 2026-09-17 token 统计改造：决策器从 `.call().content()` 改为 `.call().chatResponse()`
 * ——`content()` 只给文本，拿不到 metadata 里的 usage，Agent 链的 token 因此全丢。
 * 所以这里的 stub 也从 `content()` 换成 `chatResponse()`。
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

    /** 只带文本的响应：metadata 为 null，验证 usage 取值对 null 安全。 */
    private static ChatResponse textResponse(String text) {
        ChatResponse response = mock(ChatResponse.class);
        when(response.getResult())
                .thenReturn(new Generation(new AssistantMessage(text == null ? "" : text)));
        return response;
    }

    /** 带 usage 的响应。 */
    private static ChatResponse usageResponse(String text, int prompt, int completion, int total) {
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(prompt);
        when(usage.getCompletionTokens()).thenReturn(completion);
        when(usage.getTotalTokens()).thenReturn(total);

        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        when(metadata.getUsage()).thenReturn(usage);

        ChatResponse response = mock(ChatResponse.class);
        when(response.getResult())
                .thenReturn(new Generation(new AssistantMessage(text == null ? "" : text)));
        when(response.getMetadata()).thenReturn(metadata);
        return response;
    }

    @Test
    void parsesValidDashboardDecision() {
        when(callSpec.chatResponse()).thenAnswer(inv -> textResponse(
                "{\"actionType\":\"QUERY_LEARNING_DASHBOARD\",\"input\":{\"planRef\":\"Redis\"}}"
        ));

        AgentStepDecision decision = decider.decide("今天学什么", "", 1, 5, new TokenUsageAccumulator());

        assertThat(decision).isNotNull();
        assertThat(decision.actionType()).isEqualTo(AgentStepActionType.QUERY_LEARNING_DASHBOARD);
        assertThat(decision.input()).containsEntry("planRef", "Redis");
    }

    @Test
    void parsesFinalAnswerDecisionWithAnswerText() {
        when(callSpec.chatResponse()).thenAnswer(inv -> textResponse(
                "{\"actionType\":\"FINAL_ANSWER\",\"input\":{\"answer\":\"今天建议学 Redis 持久化\"}}"
        ));

        AgentStepDecision decision = decider.decide("今天学什么", "观察1", 2, 5, new TokenUsageAccumulator());

        assertThat(decision.actionType()).isEqualTo(AgentStepActionType.FINAL_ANSWER);
        assertThat(decision.input()).containsEntry("answer", "今天建议学 Redis 持久化");
    }

    @Test
    void stripsMarkdownFenceBeforeParsing() {
        when(callSpec.chatResponse()).thenAnswer(inv -> textResponse(
                "```json\n{\"actionType\":\"ASK_USER\",\"input\":{\"question\":\"选哪个计划？\"}}\n```"));

        AgentStepDecision decision = decider.decide("今天学什么", "", 1, 5, new TokenUsageAccumulator());

        assertThat(decision.actionType()).isEqualTo(AgentStepActionType.ASK_USER);
        assertThat(decision.input()).containsEntry("question", "选哪个计划？");
    }

    @Test
    void repairsOnceWhenFirstOutputIsInvalidJson() {
        when(callSpec.chatResponse())
                .thenAnswer(inv -> textResponse("我不是 JSON"))
                .thenAnswer(inv -> textResponse("{\"actionType\":\"QUERY_MEMORY\",\"input\":{\"question\":\"学习偏好\"}}"));

        AgentStepDecision decision = decider.decide("今天学什么", "", 1, 5, new TokenUsageAccumulator());

        assertThat(decision).isNotNull();
        assertThat(decision.actionType()).isEqualTo(AgentStepActionType.QUERY_MEMORY);
    }

    @Test
    void returnsNullWhenBothAttemptsFail() {
        when(callSpec.chatResponse())
                .thenAnswer(inv -> textResponse("还是不是 JSON"))
                .thenAnswer(inv -> textResponse("依旧不是 JSON"));

        AgentStepDecision decision = decider.decide("今天学什么", "", 1, 5, new TokenUsageAccumulator());

        assertThat(decision).isNull();
    }

    @Test
    void rejectsActionOutsideWhitelist() {
        when(callSpec.chatResponse()).thenAnswer(inv -> textResponse("{\"actionType\":\"DELETE_EVERYTHING\",\"input\":{}}"));

        AgentStepDecision decision = decider.decide("今天学什么", "", 1, 5, new TokenUsageAccumulator());

        assertThat(decision).isNull();
    }

    @Test
    void returnsNullWhenLlmCallThrows() {
        when(callSpec.chatResponse()).thenThrow(new RuntimeException("API 调用失败"));

        AgentStepDecision decision = decider.decide("今天学什么", "", 1, 5, new TokenUsageAccumulator());

        assertThat(decision).isNull();
    }

    @Test
    void summarizeReturnsText() {
        when(callSpec.chatResponse()).thenAnswer(inv -> textResponse("建议继续学 Redis 持久化，下一阶段是缓存击穿。"));

        String summary = decider.summarize("今天学什么", "观察1\n观察2", new TokenUsageAccumulator());

        assertThat(summary).contains("缓存击穿");
    }

    @Test
    void summarizeReturnsNullOnFailure() {
        when(callSpec.chatResponse()).thenThrow(new RuntimeException("总结失败"));

        String summary = decider.summarize("今天学什么", "观察1", new TokenUsageAccumulator());

        assertThat(summary).isNull();
    }

    // ==================== token 统计（2026-09-17） ====================

    @Test
    void accumulatesUsageAcrossRepairAttempts() {
        when(callSpec.chatResponse())
                .thenAnswer(inv -> usageResponse("不是 JSON", 100, 10, 110))
                .thenAnswer(inv -> usageResponse("{\"actionType\":\"QUERY_MEMORY\",\"input\":{}}", 200, 20, 220));

        TokenUsageAccumulator usage = new TokenUsageAccumulator();
        AgentStepDecision decision = decider.decide("今天学什么", "", 1, 5, usage);

        assertThat(decision).isNotNull();
        assertThat(usage.getTotalTokens())
                .as("主调用 + repair 两次的用量都要算进本步（repair 也是真花钱的调用）")
                .isEqualTo(330);
        assertThat(usage.getPromptTokens()).isEqualTo(300);
        assertThat(usage.getCompletionTokens()).isEqualTo(30);
    }

    @Test
    void summarizeAccumulatesUsage() {
        when(callSpec.chatResponse()).thenAnswer(inv -> usageResponse("建议继续学 Redis", 500, 50, 550));

        TokenUsageAccumulator usage = new TokenUsageAccumulator();
        decider.summarize("今天学什么", "观察1", usage);

        assertThat(usage.getTotalTokens()).isEqualTo(550);
    }

    @Test
    void nullUsageArgumentIsTolerated() {
        // 出参允许为 null：调用方不需要统计时不该因为传 null 就 NPE
        when(callSpec.chatResponse())
                .thenAnswer(inv -> textResponse("{\"actionType\":\"ASK_USER\",\"input\":{\"question\":\"哪个计划？\"}}"));

        AgentStepDecision decision = decider.decide("今天学什么", "", 1, 5, null);

        assertThat(decision).isNotNull();
        assertThat(decision.actionType()).isEqualTo(AgentStepActionType.ASK_USER);
    }
}
