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

    @Test
    void learningAgentIntentRoutesToAgentAction() {
        AiIntent intent = new AiIntent();
        intent.setIntent("LEARNING_AGENT");
        intent.setConfidence(0.92);
        intent.setSuggestedAction("AGENT");
        intent.setSuggestedWorkflowType(null);
        intent.setRisk("LOW");

        AgentDecision decision = planner.decide(
                "我今天继续学 Redis，帮我安排今天学什么",
                intent,
                null,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.AGENT);
        assertThat(decision.getIntent()).isEqualTo("LEARNING_AGENT");
        assertThat(decision.getRuleHits()).contains("learning_agent_intent");
    }

    @Test
    void learningAgentIntentIsNotPulledIntoLearningPlanWorkflow() {
        // “帮我安排今天学习”会命中 looksLikeLearningPlanRequest 正则，
        // 但分类器主判 LEARNING_AGENT 时，后端不得把它拉进 LEARNING_PLAN 管道。
        AiIntent intent = new AiIntent();
        intent.setIntent("LEARNING_AGENT");
        intent.setConfidence(0.92);
        intent.setSuggestedAction("WORKFLOW");
        intent.setSuggestedWorkflowType("LEARNING_PLAN");
        intent.setRisk("LOW");

        AgentDecision decision = planner.decide(
                "帮我安排今天学习",
                intent,
                null,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isNotEqualTo(AgentAction.WORKFLOW);
        assertThat(decision.getWorkflowType()).isNull();
    }

    @Test
    void learningAgentAddTaskIntentIsNotPulledIntoLearningAssistWorkflow() {
        // V3.1：用户给出明确任务名（受控写素材）→ 分类器主判 LEARNING_AGENT；
        // LLM 若误建议 LEARNING_ASSIST workflow，后端不得放行（该诉求走 SUGGEST_WRITE 受控写动作）
        AiIntent intent = new AiIntent();
        intent.setIntent("LEARNING_AGENT");
        intent.setConfidence(0.92);
        intent.setSuggestedAction("WORKFLOW");
        intent.setSuggestedWorkflowType("LEARNING_ASSIST");
        intent.setRisk("LOW");

        AgentDecision decision = planner.decide(
                "给 Redis 计划第二阶段加一个缓存雪崩防护任务",
                intent,
                null,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isNotEqualTo(AgentAction.WORKFLOW);
        assertThat(decision.getWorkflowType()).isNull();
    }

    @Test
    void learningAgentCtaSuggestionStaysCta() {
        AiIntent intent = new AiIntent();
        intent.setIntent("LEARNING_AGENT");
        intent.setConfidence(0.60);
        intent.setSuggestedAction("CTA");
        intent.setRisk("MEDIUM");
        intent.setReason("学习目标不够明确");

        AgentDecision decision = planner.decide(
                "我最近学 Redis 有点乱",
                intent,
                null,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.CTA);
        assertThat(decision.getReason()).contains("分类器建议 CTA");
    }

    @Test
    void learningAgentSuggestionMismatchFallsBackToChat() {
        AiIntent intent = new AiIntent();
        intent.setIntent("LEARNING_AGENT");
        intent.setConfidence(0.92);
        intent.setSuggestedAction("CHAT");
        intent.setRisk("LOW");

        AgentDecision decision = planner.decide(
                "我今天继续学 Redis",
                intent,
                null,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.CHAT);
        assertThat(decision.getReason()).contains("建议不一致");
    }

    @Test
    void articleAgentIntentRoutesToAgentAction() {
        // V2.5：文章页 + 模糊优化诉求 → 文章 Agent Runtime
        AiIntent intent = new AiIntent();
        intent.setIntent("ARTICLE_AGENT");
        intent.setConfidence(0.9);
        intent.setSuggestedAction("AGENT");
        intent.setSuggestedWorkflowType(null);
        intent.setRisk("LOW");

        PageContextDTO pageContext = new PageContextDTO();
        pageContext.setPageType("article-detail");
        pageContext.setArticleId("12");

        AgentDecision decision = planner.decide(
                "帮我看看这篇文章还能怎么改",
                intent,
                pageContext,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.AGENT);
        assertThat(decision.getIntent()).isEqualTo("ARTICLE_AGENT");
        assertThat(decision.getRuleHits()).contains("article_agent_intent");
        assertThat(decision.getRuleHits()).contains("article_context_valid");
    }

    @Test
    void articleAgentWithoutArticleContextFallsBackToCta() {
        // V2.5：文章 Agent 缺 articleId（非文章页说同样的话）→ 降级 CTA，不猜
        AiIntent intent = new AiIntent();
        intent.setIntent("ARTICLE_AGENT");
        intent.setConfidence(0.9);
        intent.setSuggestedAction("AGENT");
        intent.setRisk("LOW");

        AgentDecision decision = planner.decide(
                "帮我看看这篇文章还能怎么改",
                intent,
                null,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.CTA);
        assertThat(decision.getRuleHits()).contains("article_context_missing");
    }

    @Test
    void articleAgentCtaSuggestionStaysCta() {
        AiIntent intent = new AiIntent();
        intent.setIntent("ARTICLE_AGENT");
        intent.setConfidence(0.6);
        intent.setSuggestedAction("CTA");
        intent.setRisk("MEDIUM");
        intent.setReason("优化诉求不够明确");

        PageContextDTO pageContext = new PageContextDTO();
        pageContext.setPageType("article-detail");
        pageContext.setArticleId("12");

        AgentDecision decision = planner.decide(
                "帮我看看这篇文章",
                intent,
                pageContext,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.CTA);
        assertThat(decision.getReason()).contains("分类器建议 CTA");
    }

    @Test
    void articleAgentSuggestionMismatchFallsBackToChat() {
        AiIntent intent = new AiIntent();
        intent.setIntent("ARTICLE_AGENT");
        intent.setConfidence(0.9);
        intent.setSuggestedAction("CHAT");
        intent.setRisk("LOW");

        PageContextDTO pageContext = new PageContextDTO();
        pageContext.setPageType("article-detail");
        pageContext.setArticleId("12");

        AgentDecision decision = planner.decide(
                "帮我看看这篇文章",
                intent,
                pageContext,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.CHAT);
        assertThat(decision.getReason()).contains("建议不一致");
    }

    @Test
    void articleAgentIntentIsNotPulledIntoOptimizeArticleWorkflow() {
        // V2.5：分类器主判 ARTICLE_AGENT 时，不得被拉进 OPTIMIZE_ARTICLE Workflow 管道
        AiIntent intent = new AiIntent();
        intent.setIntent("ARTICLE_AGENT");
        intent.setConfidence(0.9);
        intent.setSuggestedAction("WORKFLOW");
        intent.setSuggestedWorkflowType("OPTIMIZE_ARTICLE");
        intent.setRisk("LOW");

        PageContextDTO pageContext = new PageContextDTO();
        pageContext.setPageType("article-detail");
        pageContext.setArticleId("12");

        AgentDecision decision = planner.decide(
                "帮我看看这篇文章",
                intent,
                pageContext,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isNotEqualTo(AgentAction.WORKFLOW);
        assertThat(decision.getWorkflowType()).isNull();
    }

    @Test
    void generalChatWithNeedsThinkingRoutesToAgentAction() {
        // V3：GENERAL_CHAT + needsThinking=true → 通用 Agent Runtime（归一化收在 Planner 单点）
        AiIntent intent = new AiIntent();
        intent.setIntent("GENERAL_CHAT");
        intent.setConfidence(0.85);
        intent.setSuggestedAction("CHAT");
        intent.setSuggestedWorkflowType(null);
        intent.setRisk("LOW");
        intent.setNeedsThinking(true);
        intent.setNeedsThinkingReason("问题指向前文讨论的方案");

        AgentDecision decision = planner.decide(
                "你觉得我现在这套方案还有什么问题",
                intent,
                null,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.AGENT);
        assertThat(decision.getIntent()).isEqualTo("GENERAL_CHAT");
        assertThat(decision.getRuleHits()).contains("general_agent_needs_thinking");
    }

    @Test
    void generalChatWithoutNeedsThinkingStaysChat() {
        // V3：needsThinking=false 维持现状（直达普通聊天 + 自动 RAG）
        AiIntent intent = new AiIntent();
        intent.setIntent("GENERAL_CHAT");
        intent.setConfidence(0.98);
        intent.setSuggestedAction("CHAT");
        intent.setRisk("LOW");
        intent.setNeedsThinking(false);

        AgentDecision decision = planner.decide(
                "什么是缓存穿透",
                intent,
                null,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.CHAT);
        assertThat(decision.getRuleHits()).doesNotContain("general_agent_needs_thinking");
    }

    @Test
    void generalChatWithNeedsThinkingGuestFallsBackToChat() {
        // V3：游客恒 false（无记忆、无归属），不进通用 Runtime
        AiIntent intent = new AiIntent();
        intent.setIntent("GENERAL_CHAT");
        intent.setConfidence(0.85);
        intent.setSuggestedAction("CHAT");
        intent.setRisk("LOW");
        intent.setNeedsThinking(true);

        AgentDecision decision = planner.decide(
                "结合我最近的情况给个建议",
                intent,
                null,
                null,
                null
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.CHAT);
        assertThat(decision.getRuleHits()).contains("guest_needs_thinking_forbidden");
    }

    @Test
    void generalChatWithNeedsThinkingSuggestionMismatchFallsBackToChat() {
        // V3：分类器内部不一致（needsThinking=true 但建议 TOOL）→ 普通聊天
        AiIntent intent = new AiIntent();
        intent.setIntent("GENERAL_CHAT");
        intent.setConfidence(0.85);
        intent.setSuggestedAction("TOOL");
        intent.setSuggestedWorkflowType(null);
        intent.setRisk("LOW");
        intent.setNeedsThinking(true);

        AgentDecision decision = planner.decide(
                "你觉得我现在这套方案还有什么问题",
                intent,
                null,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.CHAT);
        assertThat(decision.getReason()).contains("建议不一致");
    }

    @Test
    void generalChatWithNeedsThinkingCtaStaysCta() {
        // V3：分类器建议 CTA → 保持 CTA（澄清优先）
        AiIntent intent = new AiIntent();
        intent.setIntent("GENERAL_CHAT");
        intent.setConfidence(0.6);
        intent.setSuggestedAction("CTA");
        intent.setRisk("MEDIUM");
        intent.setNeedsThinking(true);

        AgentDecision decision = planner.decide(
                "你觉得呢",
                intent,
                null,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.CTA);
    }

    @Test
    void createWordingLearningPlanRequestAutoStarts() {
        // V3.0：明确诉求「创建…学习规划」不再被正则漏匹配降级 CTA
        AiIntent intent = workflowIntent("LEARNING_PLAN", AiWorkflowType.LEARNING_PLAN, 0.95, "LOW");

        AgentDecision decision = planner.decide(
                "创建关于Java的学习规划，两个月",
                intent,
                null,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.WORKFLOW);
        assertThat(decision.getWorkflowType()).isEqualTo(AiWorkflowType.LEARNING_PLAN);
    }

    @Test
    void giveMeWordingLearningPlanRequestAutoStarts() {
        // V3.0：「给我一个学习计划」变体（无「帮我」前缀）也命中
        AiIntent intent = workflowIntent("LEARNING_PLAN", AiWorkflowType.LEARNING_PLAN, 0.95, "LOW");

        AgentDecision decision = planner.decide(
                "结合我最近的情况，给我一个学习计划",
                intent,
                null,
                1L,
                session(10L)
        );

        assertThat(decision.getAction()).isEqualTo(AgentAction.WORKFLOW);
        assertThat(decision.getWorkflowType()).isEqualTo(AiWorkflowType.LEARNING_PLAN);
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
