package com.hailin.blogsystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.agent.AgentStepDecision;
import com.hailin.blogsystem.ai.AiJudgeModelSupport;
import com.hailin.blogsystem.ai.agent.ArticleAgentStepDecider;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 文章域决策器测试（V2.5）。
 * mock ChatClient 返回固定 JSON，验证 QUERY_ARTICLE / SUGGEST_WORKFLOW 解析与修复。
 *
 * 2026-09-17 token 统计改造：决策器改用 `.call().chatResponse()`（content() 拿不到 usage），
 * 所以 stub 也从 content() 换成 chatResponse()。
 *
 * 响应对象一律用 `thenAnswer` **延迟构造**：helper 内部要调 mock(...)，
 * 若直接写在 `thenReturn(textResponse(...))` 的参数里，会在 stubbing 尚未收尾时
 * 插入新的 mock 操作 → Mockito 抛 UnfinishedStubbingException。
 */
class ArticleAgentStepDeciderTests {

    private ChatClient.Builder builder;
    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callSpec;
    private ArticleAgentStepDecider decider;

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

        decider = new ArticleAgentStepDecider(builder, new ObjectMapper(), new AiJudgeModelSupport(""));
    }

    /** 只带文本的响应：metadata 为 null，验证 usage 取值对 null 安全。 */
    private static ChatResponse textResponse(String text) {
        ChatResponse response = mock(ChatResponse.class);
        when(response.getResult())
                .thenReturn(new Generation(new AssistantMessage(text == null ? "" : text)));
        return response;
    }

    @Test
    void parsesQueryArticleDecision() {
        when(callSpec.chatResponse()).thenAnswer(inv -> textResponse(
                "{\"actionType\":\"QUERY_ARTICLE\",\"input\":{\"articleId\":\"12\"}}"
        ));

        AgentStepDecision decision = decider.decide("帮我看看这篇文章", "", 1, 5, null);

        assertThat(decision).isNotNull();
        assertThat(decision.actionType()).isEqualTo(AgentStepActionType.QUERY_ARTICLE);
        assertThat(decision.input()).containsEntry("articleId", "12");
    }

    @Test
    void parsesSuggestWorkflowDecisionWithOptimizeArticle() {
        when(callSpec.chatResponse()).thenAnswer(inv -> textResponse(
                "{\"actionType\":\"SUGGEST_WORKFLOW\",\"input\":{\"workflowType\":\"OPTIMIZE_ARTICLE\",\"reason\":\"缺少小标题\"}}"
        ));

        AgentStepDecision decision = decider.decide("感觉写得不太好", "当前文章分析：...", 2, 5, null);

        assertThat(decision.actionType()).isEqualTo(AgentStepActionType.SUGGEST_WORKFLOW);
        assertThat(decision.input()).containsEntry("workflowType", "OPTIMIZE_ARTICLE");
    }

    @Test
    void parsesSuggestWriteDecisionWithArticleTitleUpdate() {
        // V3.4：内层 actionType=UPDATE_ARTICLE_TITLE + newTitle（外层 SUGGEST_WRITE，双层分层语义）
        when(callSpec.chatResponse()).thenAnswer(inv -> textResponse(
                "{\"actionType\":\"SUGGEST_WRITE\",\"input\":{\"actionType\":\"UPDATE_ARTICLE_TITLE\",\"newTitle\":\"Redis 实战\"}}"
        ));

        AgentStepDecision decision = decider.decide("把标题改成 Redis 实战", "当前文章分析：...", 2, 5, null);

        assertThat(decision.actionType()).isEqualTo(AgentStepActionType.SUGGEST_WRITE);
        assertThat(decision.input())
                .containsEntry("actionType", "UPDATE_ARTICLE_TITLE")
                .containsEntry("newTitle", "Redis 实战");
    }

    @Test
    void repairsOnceWhenFirstOutputIsInvalidJson() {
        when(callSpec.chatResponse())
                .thenAnswer(inv -> textResponse("我不是 JSON"))
                .thenAnswer(inv -> textResponse("{\"actionType\":\"FINAL_ANSWER\",\"input\":{\"answer\":\"建议补充小标题\"}}"));

        AgentStepDecision decision = decider.decide("帮我看看这篇文章", "", 1, 5, null);

        assertThat(decision).isNotNull();
        assertThat(decision.actionType()).isEqualTo(AgentStepActionType.FINAL_ANSWER);
    }

    @Test
    void returnsNullWhenBothAttemptsFail() {
        when(callSpec.chatResponse())
                .thenAnswer(inv -> textResponse("还是不是 JSON"))
                .thenAnswer(inv -> textResponse("依旧不是 JSON"));

        AgentStepDecision decision = decider.decide("帮我看看这篇文章", "", 1, 5, null);

        assertThat(decision).isNull();
    }

    @Test
    void summarizeReturnsText() {
        when(callSpec.chatResponse()).thenAnswer(inv -> textResponse("文章结构可以，建议补充小标题分层。"));

        String summary = decider.summarize("帮我看看这篇文章", "当前文章分析：...", null);

        assertThat(summary).contains("小标题");
    }
}
