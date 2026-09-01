package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.agent.AgentStepDecision;
import com.hailin.blogsystem.ai.agent.GeneralAgentActionExecutorImpl;
import com.hailin.blogsystem.ai.memory.AiEpisodicMemoryRetrieveService;
import com.hailin.blogsystem.ai.memory.AiMemoryRetrieveService;
import com.hailin.blogsystem.ai.rag.ArticleRagSearchService;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import com.hailin.blogsystem.entity.dto.ArticleRagSearchResult;
import com.hailin.blogsystem.entity.dto.ArticleRagSearchStrategy;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import com.hailin.blogsystem.entity.dto.EpisodicMemoryRagContext;
import com.hailin.blogsystem.entity.dto.MemoryRagContext;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通用域动作执行器测试（V3 通用思考模式）。
 * 验证 QUERY_MEMORY（V3 增量）/ SEARCH_RAG（证据补充）的观察输出与失败语义。
 */
class GeneralAgentActionExecutorTests {

    private AiMemoryRetrieveService aiMemoryRetrieveService;
    private AiEpisodicMemoryRetrieveService aiEpisodicMemoryRetrieveService;
    private ArticleRagSearchService articleRagSearchService;
    private GeneralAgentActionExecutorImpl executor;

    @BeforeEach
    void setUp() {
        aiMemoryRetrieveService = mock(AiMemoryRetrieveService.class);
        aiEpisodicMemoryRetrieveService = mock(AiEpisodicMemoryRetrieveService.class);
        articleRagSearchService = mock(ArticleRagSearchService.class);
        executor = new GeneralAgentActionExecutorImpl(
                aiMemoryRetrieveService,
                aiEpisodicMemoryRetrieveService,
                articleRagSearchService,
                new BlogAiProperties()
        );
    }

    @Test
    void memoryReturnsSummaryWithRetrievals() {
        when(aiMemoryRetrieveService.retrieve(eq(100L), any())).thenReturn(List.of(
                new MemoryRagContext(1L, 100L, "USER_PREFERENCE", "偏好",
                        "用户正在学 Redis 缓存，偏好从实战切入", null, null)
        ));
        when(aiEpisodicMemoryRetrieveService.retrieveForPrompt(eq(100L), any(), any()))
                .thenReturn(List.of(
                        new EpisodicMemoryRagContext(2L, 100L, "blog-system", "EPISODIC", "情景",
                                "上周用户卡在缓存击穿，希望拆小任务", null, null, null)
                ));

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_MEMORY)
                        .withInput(Map.of("question", "用户最近情况")),
                100L,
                null
        );

        assertThat(observation).contains("记忆摘要");
        assertThat(observation).contains("语义记忆(USER_PREFERENCE)");
        assertThat(observation).contains("用户正在学 Redis 缓存");
        assertThat(observation).contains("情景记忆(EPISODIC)");
        assertThat(observation).contains("上周用户卡在缓存击穿");
    }

    @Test
    void memoryWithoutQueryDefaultsToGenericQuery() {
        when(aiMemoryRetrieveService.retrieve(eq(100L), any())).thenReturn(List.of());
        when(aiEpisodicMemoryRetrieveService.retrieveForPrompt(eq(100L), any(), any()))
                .thenReturn(List.of());

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_MEMORY),
                100L,
                null
        );

        // 无检索词时默认「用户情况」召回，不炸 Loop
        assertThat(observation).contains("记忆摘要");
        verify(aiMemoryRetrieveService).retrieve(eq(100L), eq("用户情况"));
    }

    @Test
    void memoryRetrievalFailureStillReturnsSummary() {
        when(aiMemoryRetrieveService.retrieve(eq(100L), any()))
                .thenThrow(new RuntimeException("ES 连接失败"));
        when(aiEpisodicMemoryRetrieveService.retrieveForPrompt(eq(100L), any(), any()))
                .thenThrow(new RuntimeException("ES 连接失败"));

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_MEMORY)
                        .withInput(Map.of("question", "用户最近情况")),
                100L,
                null
        );

        assertThat(observation).contains("语义记忆检索失败");
        assertThat(observation).contains("情景记忆检索失败");
    }

    @Test
    void ragReturnsContextsFormatted() {
        when(articleRagSearchService.search(any(), any()))
                .thenReturn(new ArticleRagSearchResult(
                        ArticleRagSearchStrategy.HYBRID,
                        List.of(new ArticleRagContext(1L, "Redis缓存设计", 0,
                                "缓存穿透的防护思路是互斥锁加逻辑过期"))
                ));

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.SEARCH_RAG)
                        .withInput(Map.of("keyword", "缓存穿透")),
                100L,
                null
        );

        assertThat(observation).contains("站内文章知识检索结果");
        assertThat(observation).contains("《Redis缓存设计》");
        assertThat(observation).contains("缓存穿透的防护思路");
    }

    @Test
    void ragWithoutKeywordReturnsHint() {
        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.SEARCH_RAG),
                100L,
                null
        );

        assertThat(observation).contains("缺少关键词");
    }

    @Test
    void ragWithNoResultReturnsEmptyText() {
        when(articleRagSearchService.search(any(), any()))
                .thenReturn(new ArticleRagSearchResult(ArticleRagSearchStrategy.EMPTY, List.of()));

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.SEARCH_RAG)
                        .withInput(Map.of("keyword", "Redis 缓存击穿")),
                100L,
                null
        );

        assertThat(observation).contains("没有检索到");
    }

    @Test
    void unauthenticatedReturnsHint() {
        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_MEMORY),
                null,
                null
        );

        assertThat(observation).contains("未登录");
    }

    @Test
    void domainActionMustNotReachGeneralExecutor() {
        assertThatThrownBy(() -> executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE),
                100L,
                new PageContextDTO()
        )).isInstanceOf(UnsupportedOperationException.class);
    }
}
