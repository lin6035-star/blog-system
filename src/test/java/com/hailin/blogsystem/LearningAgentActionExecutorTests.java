package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.agent.AgentStepDecision;
import com.hailin.blogsystem.ai.agent.LearningAgentActionExecutorImpl;
import com.hailin.blogsystem.ai.memory.AiEpisodicMemoryRetrieveService;
import com.hailin.blogsystem.ai.memory.AiMemoryRetrieveService;
import com.hailin.blogsystem.ai.rag.ArticleRagSearchService;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.dto.ArticleRagSearchResult;
import com.hailin.blogsystem.entity.dto.ArticleRagSearchStrategy;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import com.hailin.blogsystem.service.LearningPlansService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Learning Agent 动作执行器测试。
 * 全部 mock Service，验证三个只读动作的 observation 输出与失败语义。
 */
class LearningAgentActionExecutorTests {

    private LearningPlansService learningPlansService;
    private AiMemoryRetrieveService aiMemoryRetrieveService;
    private AiEpisodicMemoryRetrieveService aiEpisodicMemoryRetrieveService;
    private ArticleRagSearchService articleRagSearchService;
    private LearningAgentActionExecutorImpl executor;

    @BeforeEach
    void setUp() {
        learningPlansService = mock(LearningPlansService.class);
        aiMemoryRetrieveService = mock(AiMemoryRetrieveService.class);
        aiEpisodicMemoryRetrieveService = mock(AiEpisodicMemoryRetrieveService.class);
        articleRagSearchService = mock(ArticleRagSearchService.class);

        BlogAiProperties properties = new BlogAiProperties();
        executor = new LearningAgentActionExecutorImpl(
                learningPlansService,
                aiMemoryRetrieveService,
                aiEpisodicMemoryRetrieveService,
                articleRagSearchService,
                properties
        );
    }

    @Test
    void notLoggedInReturnsFriendlyText() {
        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_LEARNING_DASHBOARD),
                null,
                null
        );

        assertThat(observation).isEqualTo("当前未登录，无法查询学习信息。");
    }

    @Test
    void dashboardWithNoPlansReturnsEmptyText() {
        when(learningPlansService.listByUser(100L)).thenReturn(List.of());

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_LEARNING_DASHBOARD),
                100L,
                null
        );

        assertThat(observation).isEqualTo("当前没有任何学习计划。");
    }

    @Test
    void dashboardListsPlanSummaries() {
        LearningPlans planA = plan(1L, "Redis 学习计划", "ACTIVE");
        LearningPlans planB = plan(2L, "Java 并发计划", "ACTIVE");
        when(learningPlansService.listByUser(100L)).thenReturn(List.of(planA, planB));

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_LEARNING_DASHBOARD),
                100L,
                null
        );

        assertThat(observation).contains("共 2 个计划");
        assertThat(observation).contains("Redis 学习计划");
        assertThat(observation).contains("Java 并发计划");
    }

    @Test
    void dashboardWithPlanRefShowsTargetPlanDetail() {
        LearningPlans plan = plan(1L, "Redis 学习计划", "ACTIVE");
        when(learningPlansService.listByUser(100L)).thenReturn(List.of(plan));
        when(learningPlansService.matchPlansByMessage(eq(100L), any()))
                .thenReturn(List.of(plan));
        // getDetail 未 mock，返回 null：详情分支容错，不炸

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_LEARNING_DASHBOARD)
                        .withInput(Map.of("planRef", "Redis")),
                100L,
                null
        );

        assertThat(observation).contains("目标计划");
        assertThat(observation).contains("Redis 学习计划");
    }

    @Test
    void dashboardWithMultipleMatchesListsCandidatesInsteadOfGuessing() {
        LearningPlans planA = plan(1L, "Redis 学习计划", "ACTIVE");
        LearningPlans planB = plan(2L, "Redis 进阶计划", "ACTIVE");
        when(learningPlansService.listByUser(100L)).thenReturn(List.of(planA, planB));
        when(learningPlansService.matchPlansByMessage(eq(100L), any()))
                .thenReturn(List.of(planA, planB));

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_LEARNING_DASHBOARD)
                        .withInput(Map.of("planRef", "Redis")),
                100L,
                null
        );

        // 并列 = 歧义：只列候选，不猜（Agent 下一步应 ASK_USER）
        assertThat(observation).contains("请确认具体是哪个");
        assertThat(observation).contains("Redis 学习计划");
        assertThat(observation).contains("Redis 进阶计划");
    }

    @Test
    void dashboardWithoutPlanRefAndSingleActiveShowsDetail() {
        LearningPlans plan = plan(1L, "Redis 学习计划", "ACTIVE");
        when(learningPlansService.listByUser(100L)).thenReturn(List.of(plan));
        // matchPlansByMessage 未 mock，返回 null/空：点名分支不触发

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_LEARNING_DASHBOARD),
                100L,
                null
        );

        // 未点名且唯一 ACTIVE → 直接详情（getDetail 未 mock 返回 null，详情内部容错）
        assertThat(observation).contains("目标计划");
    }

    @Test
    void dashboardWithoutPlanRefAndMultipleActiveListsCandidates() {
        LearningPlans planA = plan(1L, "Redis 学习计划", "ACTIVE");
        LearningPlans planB = plan(2L, "Java 并发计划", "ACTIVE");
        when(learningPlansService.listByUser(100L)).thenReturn(List.of(planA, planB));

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_LEARNING_DASHBOARD),
                100L,
                null
        );

        // 多个 ACTIVE 只列候选
        assertThat(observation).contains("共 2 个计划");
        assertThat(observation).contains("1. 《Redis 学习计划》");
        assertThat(observation).contains("2. 《Java 并发计划》");
    }

    @Test
    void dashboardPlanRefNotFoundDoesNotFallbackToSingleActivePlan() {
        // 用户点名 RocketMQ，系统只有 Redis 计划：
        // 必须只提示未找到 + 候选，绝不能 fallback 出《Redis 计划》详情误导 Agent
        LearningPlans plan = plan(1L, "Redis 学习计划", "ACTIVE");
        when(learningPlansService.listByUser(100L)).thenReturn(List.of(plan));
        when(learningPlansService.matchPlansByMessage(eq(100L), any()))
                .thenReturn(List.of());

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_LEARNING_DASHBOARD)
                        .withInput(Map.of("planRef", "RocketMQ")),
                100L,
                null
        );

        assertThat(observation).contains("没有找到");
        assertThat(observation).doesNotContain("目标计划");
        // 候选列表仍给出（Agent 下一步可 ASK_USER 让用户选）
        assertThat(observation).contains("Redis 学习计划");
    }

    @Test
    void memoryReturnsSummaryWithEmptyRetrieval() {
        when(aiMemoryRetrieveService.retrieve(eq(100L), any())).thenReturn(List.of());
        when(aiEpisodicMemoryRetrieveService.retrieveForPrompt(eq(100L), any(), any()))
                .thenReturn(List.of());

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_MEMORY)
                        .withInput(Map.of("question", "学习偏好")),
                100L,
                null
        );

        assertThat(observation).contains("记忆摘要");
    }

    @Test
    void memoryRetrievalFailureStillReturnsSummary() {
        when(aiMemoryRetrieveService.retrieve(eq(100L), any()))
                .thenThrow(new RuntimeException("ES 连接失败"));
        when(aiEpisodicMemoryRetrieveService.retrieveForPrompt(eq(100L), any(), any()))
                .thenThrow(new RuntimeException("ES 连接失败"));

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_MEMORY)
                        .withInput(Map.of("question", "学习偏好")),
                100L,
                null
        );

        // 记忆检索失败不能炸掉 Agent Loop，返回带失败标记的摘要
        assertThat(observation).contains("语义记忆检索失败");
        assertThat(observation).contains("情景记忆检索失败");
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
    void terminalActionMustNotReachExecutor() {
        assertThatThrownBy(() -> executor.execute(
                AgentStepDecision.of(AgentStepActionType.ASK_USER),
                100L,
                null
        )).isInstanceOf(UnsupportedOperationException.class);
    }

    private LearningPlans plan(Long id, String title, String status) {
        LearningPlans plan = new LearningPlans();
        plan.setId(id);
        plan.setTitle(title);
        plan.setStatus(status);
        return plan;
    }
}
