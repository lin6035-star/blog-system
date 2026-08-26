package com.hailin.blogsystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.planner.AgentPlannerSupport;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.AiSessions;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.dto.AgentAction;
import com.hailin.blogsystem.entity.dto.AgentDecision;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.AiWorkflowType;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.mapper.AiWorkflowRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Learning Agent V1 Planner 裁决测试。
 * 这里不测 LLM，只测“分类器建议 + 后端规则双签 + 成本护栏”的确定性结果。
 */
class AgentPlannerSupportTests {

    private BlogAiProperties properties;
    private AiWorkflowRunMapper workflowRunMapper;
    private AgentPlannerSupport planner;

    @BeforeEach
    void setUp() {
        properties = new BlogAiProperties();
        workflowRunMapper = mock(AiWorkflowRunMapper.class);
        when(workflowRunMapper.selectList(any())).thenReturn(List.of());
        planner = new AgentPlannerSupport(properties, workflowRunMapper, new ObjectMapper());
    }

    @Test
    void queryRequestUsesDashboardToolEvenWhenClassifierMisjudgesPlanIntent() {
        AiIntent intent = workflowIntent("LEARNING_PLAN", AiWorkflowType.LEARNING_PLAN, 0.95, "LOW");

        AgentDecision decision = planner.decideLearning(
                "你帮我看看我有几个学习规划",
                intent,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.TOOL);
        assertThat(decision.getToolName()).isEqualTo("getLearningDashboard");
        assertThat(decision.getRuleHits()).contains("learning_plan_query");
    }

    @Test
    void planRequestAutoStartsWhenLlmSuggestionAndRuleBothMatch() {
        AiIntent intent = workflowIntent("LEARNING_PLAN", AiWorkflowType.LEARNING_PLAN, 0.91, "LOW");

        AgentDecision decision = planner.decideLearning(
                "我想学习 Redis",
                intent,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.WORKFLOW);
        assertThat(decision.getWorkflowType()).isEqualTo(AiWorkflowType.LEARNING_PLAN);
        assertThat(decision.getRuleHits()).contains("learning_plan_rule");
    }

    @Test
    void workflowSuggestionCanPassWhenIntentIsMisclassifiedButSuggestedTypeMatches() {
        AiIntent intent = workflowIntent(
                "LEARNING_PLAN_QUERY",
                AiWorkflowType.LEARNING_PLAN,
                0.95,
                "LOW"
        );

        AgentDecision decision = planner.decideLearning(
                "我想系统学习消息队列",
                intent,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.WORKFLOW);
        assertThat(decision.getWorkflowType()).isEqualTo(AiWorkflowType.LEARNING_PLAN);
        assertThat(decision.getRuleHits()).contains("learning_plan_rule");
    }

    @Test
    void progressRequestAutoStartsWhenLlmSuggestionAndRuleBothMatch() {
        AiIntent intent = workflowIntent("LEARNING_PROGRESS", AiWorkflowType.LEARNING_PROGRESS, 0.91, "LOW");

        AgentDecision decision = planner.decideLearning(
                "帮我压缩一下学习计划的第二阶段",
                intent,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.WORKFLOW);
        assertThat(decision.getWorkflowType()).isEqualTo(AiWorkflowType.LEARNING_PROGRESS);
        assertThat(decision.getRuleHits()).contains("learning_progress_rule");
    }

    @Test
    void progressRequestWithPlanNamedBeforeAdjustmentVerbAutoStarts() {
        AiIntent intent = workflowIntent(
                "LEARNING_PROGRESS",
                AiWorkflowType.LEARNING_PROGRESS,
                0.95,
                "LOW"
        );
        intent.setLearningPlanRef("Agent学习计划");
        intent.setLearningStageRef("第二阶段");

        AgentDecision decision = planner.decideLearning(
                "帮我把 Agent学习计划的第二阶段压缩一下",
                intent,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.WORKFLOW);
        assertThat(decision.getWorkflowType())
                .isEqualTo(AiWorkflowType.LEARNING_PROGRESS);
        assertThat(decision.getRuleHits())
                .contains("learning_progress_rule");
    }

    @Test
    void difficultyRequestAutoStartsWhenLlmSuggestionAndRuleBothMatch() {
        AiIntent intent = workflowIntent("LEARNING_ASSIST", AiWorkflowType.LEARNING_ASSIST, 0.91, "LOW");

        AgentDecision decision = planner.decideLearning(
                "Redis 计划里的缓存击穿我看不懂",
                intent,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.WORKFLOW);
        assertThat(decision.getWorkflowType()).isEqualTo(AiWorkflowType.LEARNING_ASSIST);
        assertThat(decision.getRuleHits()).contains("learning_assist_rule");
    }

    @Test
    void difficultyRequestWithTingNanStartsLearningAssistWorkflow() {
        AiIntent intent = workflowIntent("LEARNING_ASSIST", AiWorkflowType.LEARNING_ASSIST, 0.95, "LOW");

        AgentDecision decision = planner.decideLearning(
                "该计划的第二阶段就挺难的了，能帮我拆分一下吗",
                intent,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.WORKFLOW);
        assertThat(decision.getWorkflowType()).isEqualTo(AiWorkflowType.LEARNING_ASSIST);
        assertThat(decision.getRuleHits()).contains("learning_assist_rule");
    }

    @Test
    void classifierCtaSuggestionMustRemainCta() {
        AiIntent intent = new AiIntent();
        intent.setIntent("GENERAL_CHAT");
        intent.setConfidence(0.60);
        intent.setSuggestedAction("CTA");
        intent.setRisk("MEDIUM");
        intent.setReason("学习目标不够明确");

        AgentDecision decision = planner.decideLearning(
                "我最近学 Redis 有点乱",
                intent,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.CTA);
        assertThat(decision.getReason()).contains("分类器建议 CTA");
    }

    @Test
    void workflowRuleHitFallsBackToCtaWhenRiskIsNotLow() {
        AiIntent intent = workflowIntent("LEARNING_PLAN", AiWorkflowType.LEARNING_PLAN, 0.91, "MEDIUM");

        AgentDecision decision = planner.decideLearning(
                "我想学习 Redis",
                intent,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.CTA);
        assertThat(decision.getReason()).contains("风险不是 LOW");
    }

    @Test
    void workflowRuleHitFallsBackToCtaWhenConfidenceIsTooLow() {
        AiIntent intent = workflowIntent("LEARNING_PLAN", AiWorkflowType.LEARNING_PLAN, 0.69, "LOW");

        AgentDecision decision = planner.decideLearning(
                "我想学习 Redis",
                intent,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.CTA);
        assertThat(decision.getReason()).contains("置信度不足");
    }

    @Test
    void workflowRuleHitFallsBackToCtaWhenWorkflowSuggestionDoesNotMatch() {
        AiIntent intent = workflowIntent("LEARNING_PLAN", AiWorkflowType.LEARNING_PROGRESS, 0.91, "LOW");

        AgentDecision decision = planner.decideLearning(
                "我想学习 Redis",
                intent,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.CTA);
        assertThat(decision.getReason()).contains("未建议对应 Workflow");
    }

    @Test
    void autoStartLimitFallsBackToCta() {
        AiWorkflowRun first = new AiWorkflowRun();
        first.setContextJson("{\"agentAutoStarted\":true}");
        AiWorkflowRun second = new AiWorkflowRun();
        second.setContextJson("{\"agentAutoStarted\":true}");
        when(workflowRunMapper.selectList(any())).thenReturn(List.of(first, second));

        AiIntent intent = workflowIntent("LEARNING_PLAN", AiWorkflowType.LEARNING_PLAN, 0.91, "LOW");

        AgentDecision decision = planner.decideLearning(
                "我想学习 Redis",
                intent,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.CTA);
        assertThat(decision.getReason()).contains("自动拉起次数已达上限");
    }

    @Test
    void normalLearningQuestionStaysChatWhenNoPlannerRuleMatches() {
        AiIntent intent = new AiIntent();
        intent.setIntent("GENERAL_CHAT");
        intent.setConfidence(0.95);

        AgentDecision decision = planner.decideLearning(
                "Redis 是什么",
                intent,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.CHAT);
    }

    @Test
    void createArticleSuggestionUsesArticleWorkflow() {
        AiIntent intent = new AiIntent();
        intent.setIntent("CREATE_ARTICLE_WORKFLOW");
        intent.setConfidence(0.95);
        intent.setSuggestedAction("WORKFLOW");
        intent.setSuggestedWorkflowType("CREATE_ARTICLE");
        intent.setRisk("LOW");

        AgentDecision decision = planner.decide(
                "帮我写一篇 Redis 缓存文章",
                intent,
                null,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.WORKFLOW);
        assertThat(decision.getWorkflowType())
                .isEqualTo(AiWorkflowType.CREATE_ARTICLE);
        assertThat(decision.getIntent())
                .isEqualTo("CREATE_ARTICLE_WORKFLOW");
    }

    @Test
    void optimizeArticleSuggestionRequiresArticleContext() {
        AiIntent intent = new AiIntent();
        intent.setIntent("OPTIMIZE_ARTICLE_WORKFLOW");
        intent.setConfidence(0.95);
        intent.setSuggestedAction("WORKFLOW");
        intent.setSuggestedWorkflowType("OPTIMIZE_ARTICLE");
        intent.setRisk("MEDIUM");

        AgentDecision decision = planner.decide(
                "帮我优化这篇文章",
                intent,
                null,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.CTA);
        assertThat(decision.getRuleHits())
                .contains("article_context_missing");
    }

    @Test
    void optimizeArticleSuggestionUsesArticleWorkflowWhenContextExists() {
        AiIntent intent = new AiIntent();
        intent.setIntent("OPTIMIZE_ARTICLE_WORKFLOW");
        intent.setArticleId("12");
        intent.setConfidence(0.95);
        intent.setSuggestedAction("WORKFLOW");
        intent.setSuggestedWorkflowType("OPTIMIZE_ARTICLE");
        intent.setRisk("MEDIUM");

        PageContextDTO pageContext = new PageContextDTO();
        pageContext.setArticleId("12");

        AgentDecision decision = planner.decide(
                "帮我优化这篇文章",
                intent,
                pageContext,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.WORKFLOW);
        assertThat(decision.getWorkflowType())
                .isEqualTo(AiWorkflowType.OPTIMIZE_ARTICLE);
        assertThat(decision.getRuleHits())
                .contains("article_context_valid");
    }

    @Test
    void articleSearchUsesChatWithArticleRetrieval() {
        AiIntent intent = new AiIntent();
        intent.setIntent("ARTICLE_SEARCH");
        intent.setConfidence(0.96);
        intent.setSuggestedAction("CHAT");
        intent.setRisk("LOW");

        AgentDecision decision = planner.decide(
                "有没有关于 Redis 的文章",
                intent,
                null,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.CHAT);
        assertThat(decision.getIntent()).isEqualTo("ARTICLE_SEARCH");
        assertThat(decision.getRetrievalMode())
                .isEqualTo("ARTICLE_SEARCH");
    }

    @Test
    void articleDetailQuestionUsesCurrentArticleRetrieval() {
        AiIntent intent = new AiIntent();
        intent.setIntent("ARTICLE_DETAIL_QA");
        intent.setConfidence(0.96);
        intent.setSuggestedAction("CHAT");
        intent.setRisk("LOW");

        PageContextDTO pageContext = new PageContextDTO();
        pageContext.setArticleId("12");

        AgentDecision decision = planner.decide(
                "这篇文章主要讲了什么",
                intent,
                pageContext,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.CHAT);
        assertThat(decision.getRetrievalMode())
                .isEqualTo("CURRENT_ARTICLE");
    }

    @Test
    void normalChatUsesChatWithoutRetrieval() {
        AiIntent intent = new AiIntent();
        intent.setIntent("GENERAL_CHAT");
        intent.setConfidence(0.98);
        intent.setSuggestedAction("CHAT");
        intent.setRisk("LOW");

        AgentDecision decision = planner.decide(
                "Redis 是什么",
                intent,
                null,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.CHAT);
        assertThat(decision.getRetrievalMode()).isEqualTo("NONE");
    }

    @Test
    void articleActionRequiresArticleDetailContext() {
        AiIntent intent = new AiIntent();
        intent.setIntent("ARTICLE_ACTION");
        intent.setActionType("likeArticle");
        intent.setSuggestedAction("CHAT");
        intent.setConfidence(0.95);
        intent.setRisk("LOW");

        AgentDecision decision = planner.decide(
                "点赞这篇文章",
                intent,
                null,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.CTA);
    }

    @Test
    void articleActionPassesWhenContextAndActionAreValid() {
        AiIntent intent = new AiIntent();
        intent.setIntent("ARTICLE_ACTION");
        intent.setActionType("favoriteArticle");
        intent.setArticleId("12");
        intent.setSuggestedAction("CHAT");
        intent.setConfidence(0.95);
        intent.setRisk("LOW");

        PageContextDTO pageContext = new PageContextDTO();
        pageContext.setPageType("article-detail");
        pageContext.setArticleId("12");

        AgentDecision decision = planner.decide(
                "收藏这篇文章",
                intent,
                pageContext,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.CHAT);
        assertThat(decision.getClientActionType())
                .isEqualTo("favoriteArticle");
    }

    @Test
    void navigateRejectsUnknownTarget() {
        AiIntent intent = new AiIntent();
        intent.setIntent("NAVIGATE");
        intent.setTarget("unknownPage");
        intent.setSuggestedAction("CHAT");
        intent.setConfidence(0.95);
        intent.setRisk("LOW");

        AgentDecision decision = planner.decide(
                "打开那个页面",
                intent,
                null,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.CTA);
    }

    @Test
    void navigatePassesForAllowedTarget() {
        AiIntent intent = new AiIntent();
        intent.setIntent("NAVIGATE");
        intent.setTarget("drafts");
        intent.setSuggestedAction("CHAT");
        intent.setConfidence(0.95);
        intent.setRisk("LOW");

        AgentDecision decision = planner.decide(
                "打开草稿箱",
                intent,
                null,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.CHAT);
        assertThat(decision.getClientActionType())
                .isEqualTo("navigate");
    }

    @Test
    void editorActionRequiresEditorPage() {
        AiIntent intent = new AiIntent();
        intent.setIntent("EDITOR_ACTION");
        intent.setActionType("publish");
        intent.setSuggestedAction("CHAT");
        intent.setConfidence(0.95);
        intent.setRisk("MEDIUM");

        AgentDecision decision = planner.decide(
                "发布文章",
                intent,
                null,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.CTA);
    }

    @Test
    void fillArticleCannotBeTriggeredByNormalEditorAction() {
        AiIntent intent = new AiIntent();
        intent.setIntent("EDITOR_ACTION");
        intent.setActionType("fillArticle");
        intent.setSuggestedAction("CHAT");
        intent.setConfidence(0.95);
        intent.setRisk("MEDIUM");

        PageContextDTO pageContext = new PageContextDTO();
        pageContext.setPageType("editor-new");

        AgentDecision decision = planner.decide(
                "帮我填充文章",
                intent,
                pageContext,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.CTA);
    }

    private AiIntent workflowIntent(String intentType, AiWorkflowType workflowType, double confidence, String risk) {
        AiIntent intent = new AiIntent();
        intent.setIntent(intentType);
        intent.setConfidence(confidence);
        intent.setRisk(risk);
        intent.setSuggestedAction("WORKFLOW");
        intent.setSuggestedWorkflowType(workflowType.name());
        return intent;
    }

    private AiSessions session(Long id) {
        AiSessions session = new AiSessions();
        session.setId(id);
        return session;
    }
}
