package com.hailin.blogsystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.AiJudgeModelSupport;
import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.service.LearningPlansService;
import com.hailin.blogsystem.service.impl.AiIntentClassifierImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 意图分类器的 token 统计（2026-09-17）。
 *
 * 分类器的 system prompt 有 500+ 行规则，且**每条消息都要跑**——不统计就是稳定漏账。
 * 本测试锁三件事：单次采集、repair 两次都算、二次带列表分类也算。
 */
class AiIntentClassifierUsageTests {

    private ChatClient.Builder builder;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callSpec;
    private LearningPlansService learningPlansService;
    private AiIntentClassifierImpl classifier;

    @BeforeEach
    void setUp() {
        builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        learningPlansService = mock(LearningPlansService.class);

        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);

        classifier = new AiIntentClassifierImpl(
                builder, new ObjectMapper(), learningPlansService, new AiJudgeModelSupport(""));
    }

    /**
     * 响应对象一律用 thenAnswer 延迟构造——helper 内部要 mock(...)，
     * 直接写在 thenReturn(...) 参数里会触发 Mockito 的 UnfinishedStubbing。
     */
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

    private static LearningPlans plan(String title) {
        LearningPlans plan = new LearningPlans();
        plan.setId(1001L);
        plan.setTitle(title);
        return plan;
    }

    private static final String GENERAL_CHAT_JSON =
            "{\"intent\":\"GENERAL_CHAT\",\"suggestedAction\":\"CHAT\",\"risk\":\"LOW\"}";

    @Test
    void singleClassificationAccumulatesUsage() {
        when(callSpec.chatResponse()).thenAnswer(inv -> usageResponse(GENERAL_CHAT_JSON, 500, 20, 520));

        TokenUsageAccumulator usage = new TokenUsageAccumulator();
        classifier.classify("什么是缓存穿透", null, null, usage);

        assertThat(usage.getTotalTokens()).isEqualTo(520);
        assertThat(usage.getPromptTokens()).isEqualTo(500);
    }

    @Test
    void repairAttemptUsageIsAlsoCounted() {
        // 第一次不是合法 JSON → repair 一次。两次都是真花钱的调用，都要算
        when(callSpec.chatResponse())
                .thenAnswer(inv -> usageResponse("我不是 JSON", 500, 20, 520))
                .thenAnswer(inv -> usageResponse(GENERAL_CHAT_JSON, 600, 30, 630));

        TokenUsageAccumulator usage = new TokenUsageAccumulator();
        classifier.classify("什么是缓存穿透", null, null, usage);

        assertThat(usage.getTotalTokens())
                .as("主调用 + repair 两次的用量都要算进这条消息")
                .isEqualTo(1150);
    }

    @Test
    void secondClassificationWithPlanListIsAlsoCounted() {
        // 用户点名计划 + 意图是调整计划 → 分类器带计划列表**再跑一次**（needsPlanLocating）。
        // 这一小撮消息比普通消息多一次完整分类，不统计就会让这些消息的 token 明显偏低
        when(learningPlansService.listActiveByUserCached(1L))
                .thenReturn(List.of(plan("Redis 系统学习计划")));
        when(callSpec.chatResponse())
                .thenAnswer(inv -> usageResponse(
                        "{\"intent\":\"LEARNING_PROGRESS\",\"learningPlanRef\":\"Redis\","
                                + "\"suggestedAction\":\"WORKFLOW\",\"risk\":\"LOW\"}", 500, 20, 520))
                .thenAnswer(inv -> usageResponse(
                        "{\"intent\":\"LEARNING_PROGRESS\",\"learningPlanIndex\":1,"
                                + "\"suggestedAction\":\"WORKFLOW\",\"risk\":\"LOW\"}", 600, 30, 630));

        TokenUsageAccumulator usage = new TokenUsageAccumulator();
        classifier.classify("帮我调整一下 Redis 学习计划", null, 1L, usage);

        assertThat(usage.getTotalTokens())
                .as("二次分类（带计划列表）也是完整的一次 LLM 调用")
                .isEqualTo(1150);
    }

    @Test
    void nullUsageArgumentIsTolerated() {
        // 出参允许为 null：调用方不需要统计时不该因为传 null 就 NPE
        when(callSpec.chatResponse()).thenAnswer(inv -> usageResponse(GENERAL_CHAT_JSON, 500, 20, 520));

        assertThat(classifier.classify("你好", null, null, null)).isNotNull();
    }
}
