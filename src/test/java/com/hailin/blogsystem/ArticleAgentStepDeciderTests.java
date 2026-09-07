package com.hailin.blogsystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.agent.AgentStepDecision;
import com.hailin.blogsystem.ai.agent.ArticleAgentStepDecider;
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
 * 文章域决策器测试（V2.5）。
 * mock ChatClient 返回固定 JSON，验证 QUERY_ARTICLE / SUGGEST_WORKFLOW 解析与修复。
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

        decider = new ArticleAgentStepDecider(builder, new ObjectMapper());
    }

    @Test
    void parsesQueryArticleDecision() {
        when(callSpec.content()).thenReturn(
                "{\"actionType\":\"QUERY_ARTICLE\",\"input\":{\"articleId\":\"12\"}}"
        );

        AgentStepDecision decision = decider.decide("帮我看看这篇文章", "", 1, 5);

        assertThat(decision).isNotNull();
        assertThat(decision.actionType()).isEqualTo(AgentStepActionType.QUERY_ARTICLE);
        assertThat(decision.input()).containsEntry("articleId", "12");
    }

    @Test
    void parsesSuggestWorkflowDecisionWithOptimizeArticle() {
        when(callSpec.content()).thenReturn(
                "{\"actionType\":\"SUGGEST_WORKFLOW\",\"input\":{\"workflowType\":\"OPTIMIZE_ARTICLE\",\"reason\":\"缺少小标题\"}}"
        );

        AgentStepDecision decision = decider.decide("感觉写得不太好", "当前文章分析：...", 2, 5);

        assertThat(decision.actionType()).isEqualTo(AgentStepActionType.SUGGEST_WORKFLOW);
        assertThat(decision.input()).containsEntry("workflowType", "OPTIMIZE_ARTICLE");
    }

    @Test
    void parsesSuggestWriteDecisionWithArticleTitleUpdate() {
        // V3.4：内层 actionType=UPDATE_ARTICLE_TITLE + newTitle（外层 SUGGEST_WRITE，双层分层语义）
        when(callSpec.content()).thenReturn(
                "{\"actionType\":\"SUGGEST_WRITE\",\"input\":{\"actionType\":\"UPDATE_ARTICLE_TITLE\",\"newTitle\":\"Redis 实战\"}}"
        );

        AgentStepDecision decision = decider.decide("把标题改成 Redis 实战", "当前文章分析：...", 2, 5);

        assertThat(decision.actionType()).isEqualTo(AgentStepActionType.SUGGEST_WRITE);
        assertThat(decision.input())
                .containsEntry("actionType", "UPDATE_ARTICLE_TITLE")
                .containsEntry("newTitle", "Redis 实战");
    }

    @Test
    void repairsOnceWhenFirstOutputIsInvalidJson() {
        when(callSpec.content())
                .thenReturn("我不是 JSON")
                .thenReturn("{\"actionType\":\"FINAL_ANSWER\",\"input\":{\"answer\":\"建议补充小标题\"}}");

        AgentStepDecision decision = decider.decide("帮我看看这篇文章", "", 1, 5);

        assertThat(decision).isNotNull();
        assertThat(decision.actionType()).isEqualTo(AgentStepActionType.FINAL_ANSWER);
    }

    @Test
    void returnsNullWhenBothAttemptsFail() {
        when(callSpec.content())
                .thenReturn("还是不是 JSON")
                .thenReturn("依旧不是 JSON");

        AgentStepDecision decision = decider.decide("帮我看看这篇文章", "", 1, 5);

        assertThat(decision).isNull();
    }

    @Test
    void summarizeReturnsText() {
        when(callSpec.content()).thenReturn("文章结构可以，建议补充小标题分层。");

        String summary = decider.summarize("帮我看看这篇文章", "当前文章分析：...");

        assertThat(summary).contains("小标题");
    }
}
