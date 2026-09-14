package com.hailin.blogsystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.agent.AgentStepDecision;
import com.hailin.blogsystem.ai.AiJudgeModelSupport;
import com.hailin.blogsystem.ai.agent.GeneralAgentStepDecider;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 通用域决策器测试（V3 通用思考模式）。
 * mock ChatClient 返回固定 JSON，验证 QUERY_MEMORY / SEARCH_RAG 解析、修复，
 * 以及 prompt 中的「能不查就不查」「SEARCH_RAG 不是默认第一步」约束。
 */
class GeneralAgentStepDeciderTests {

    private ChatClient.Builder builder;
    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callSpec;
    private GeneralAgentStepDecider decider;

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

        decider = new GeneralAgentStepDecider(builder, new ObjectMapper(), new AiJudgeModelSupport(""));
    }

    @Test
    void parsesQueryMemoryDecision() {
        when(callSpec.content()).thenReturn(
                "{\"actionType\":\"QUERY_MEMORY\",\"input\":{\"question\":\"用户最近情况\"}}"
        );

        AgentStepDecision decision = decider.decide("结合我的情况给个建议", "", 1, 3);

        assertThat(decision).isNotNull();
        assertThat(decision.actionType()).isEqualTo(AgentStepActionType.QUERY_MEMORY);
        assertThat(decision.input()).containsEntry("question", "用户最近情况");
    }

    @Test
    void parsesSearchRagDecision() {
        when(callSpec.content()).thenReturn(
                "{\"actionType\":\"SEARCH_RAG\",\"input\":{\"keyword\":\"缓存穿透\"}}"
        );

        AgentStepDecision decision = decider.decide("缓存穿透怎么防", "记忆摘要：...", 2, 3);

        assertThat(decision.actionType()).isEqualTo(AgentStepActionType.SEARCH_RAG);
        assertThat(decision.input()).containsEntry("keyword", "缓存穿透");
    }

    @Test
    void repairsOnceWhenFirstOutputIsInvalidJson() {
        when(callSpec.content())
                .thenReturn("我不是 JSON")
                .thenReturn("{\"actionType\":\"FINAL_ANSWER\",\"input\":{\"answer\":\"直接回答\"}}");

        AgentStepDecision decision = decider.decide("什么是缓存穿透", "", 1, 3);

        assertThat(decision).isNotNull();
        assertThat(decision.actionType()).isEqualTo(AgentStepActionType.FINAL_ANSWER);
    }

    @Test
    void returnsNullWhenBothAttemptsFail() {
        when(callSpec.content())
                .thenReturn("还是不是 JSON")
                .thenReturn("依旧不是 JSON");

        AgentStepDecision decision = decider.decide("什么是缓存穿透", "", 1, 3);

        assertThat(decision).isNull();
    }

    @Test
    void summarizeReturnsText() {
        when(callSpec.content()).thenReturn("结合你的记忆，建议继续深入缓存部分。");

        String summary = decider.summarize("结合我的情况给个建议", "记忆摘要：...");

        assertThat(summary).contains("缓存部分");
    }

    @Test
    void promptForbidsUnnecessaryLookups() throws Exception {
        // 延迟双闸门的 prompt 侧约束：能不查就不查、SEARCH_RAG 只作证据补充
        Method method = GeneralAgentStepDecider.class.getDeclaredMethod("buildSystemPrompt", boolean.class);
        method.setAccessible(true);
        String prompt = (String) method.invoke(decider, false);

        assertThat(prompt).contains("能不查就不查");
        assertThat(prompt).contains("能直接答直接答");
        assertThat(prompt).contains("SEARCH_RAG 只是证据补充");
        assertThat(prompt).contains("不是默认第一步");
        assertThat(prompt).contains("先 QUERY_MEMORY");
        assertThat(prompt).doesNotContain("SUGGEST_WORKFLOW");
        assertThat(prompt).doesNotContain("SUGGEST_WRITE");
        assertThat(prompt).doesNotContain("QUERY_ARTICLE");
        assertThat(prompt).doesNotContain("QUERY_LEARNING_DASHBOARD");
    }
}
