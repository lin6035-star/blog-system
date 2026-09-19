package com.hailin.blogsystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.agent.AgentRunResult;
import com.hailin.blogsystem.ai.agent.AgentStepDecision;
import com.hailin.blogsystem.ai.agent.ArticleAgentActionExecutor;
import com.hailin.blogsystem.ai.agent.ArticleAgentRuntime;
import com.hailin.blogsystem.ai.agent.ArticleAgentStepDecider;
import com.hailin.blogsystem.ai.agent.ArticleEvidenceVerifier;
import com.hailin.blogsystem.ai.agent.ArticleSessionAnchorService;
import com.hailin.blogsystem.ai.agent.Verdict;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.AiAgentStep;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.mapper.AiAgentStepMapper;
import com.hailin.blogsystem.service.ArticlesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文章域证据收敛门集成测试（V3.11）。
 * mock 决策器/执行器/验证器，验证骨架收敛门四条路径：
 * - NEED_MORE 拦截 → 循环补查 → SUFFICIENT 放行收尾（审计留痕 + 提示 observation）
 * - ASK_USER 转问 → WAITING_USER，只落一条 ASK_USER step，无伪造 FINAL_ANSWER step
 * - S1b：验证拒绝从未满足 + maxSteps 到顶 → 保守收尾（不走 summarize）
 * - S1：目标文章从没读过 + 到顶 → 保守收尾
 */
class ArticleAgentRuntimeVerifierTests {

    private ArticleAgentStepDecider decider;
    private ArticleAgentActionExecutor executor;
    private AiAgentRunMapper runMapper;
    private AiAgentStepMapper stepMapper;
    private ArticlesService articlesService;
    private ArticleSessionAnchorService anchorService;
    private ArticleEvidenceVerifier verifier;
    private ArticleAgentRuntime runtime;

    private static PageContextDTO articleContext(String articleId) {
        PageContextDTO pageContext = new PageContextDTO();
        pageContext.setPageType("article-detail");
        pageContext.setArticleId(articleId);
        return pageContext;
    }

    @BeforeEach
    void setUp() {
        decider = mock(ArticleAgentStepDecider.class);
        executor = mock(ArticleAgentActionExecutor.class);
        runMapper = mock(AiAgentRunMapper.class);
        stepMapper = mock(AiAgentStepMapper.class);
        articlesService = mock(ArticlesService.class);
        anchorService = mock(ArticleSessionAnchorService.class);
        verifier = mock(ArticleEvidenceVerifier.class);

        when(runMapper.insert(any(AiAgentRun.class))).thenAnswer(inv -> {
            AiAgentRun run = inv.getArgument(0);
            run.setId(1L);
            return 1;
        });

        runtime = new ArticleAgentRuntime(
                decider, executor, runMapper, stepMapper, new ObjectMapper(),
                articlesService, anchorService, verifier
        );
    }

    private AgentStepDecision finalAnswer(String answer) {
        return AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                .withInput(Map.of("answer", answer));
    }

    @Test
    void needMoreInterceptsThenSupplementedAnswerCompletes() {
        // 决策：FINAL_ANSWER（草案被拦）→ QUERY_ARTICLE（补查）→ FINAL_ANSWER（验证过）
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(finalAnswer("缓存击穿那段写得不错"))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE))
                .thenReturn(finalAnswer("缓存击穿那段用互斥锁兜底，逻辑清晰，建议补充重试策略"));
        when(executor.execute(any(), any(), any()))
                .thenReturn("当前文章分析：\n- 标题：《Redis 缓存》\n- 小标题结构：缓存穿透/缓存击穿");
        when(verifier.verify(any(), any(), any(), any()))
                .thenReturn(Verdict.needMore(Verdict.MissingEvidence.ARTICLE_CONTENT,
                        "草案提到缓存击穿段，但证据里没有该段内容"))
                .thenReturn(Verdict.sufficient());

        AgentRunResult result = runtime.run(100L, 200L, "缓存击穿那段写得怎么样",
                articleContext("12"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        assertThat(result.finalAnswer()).contains("互斥锁兜底");
        assertThat(result.usedSteps()).isEqualTo(3);

        // 审计（落库顺序）：FINAL_ANSWER FAILED(拦截) + QUERY SUCCESS(补查) + FINAL_ANSWER SUCCESS
        List<AiAgentStep> steps = captureSteps();
        assertThat(steps).hasSize(3);
        assertThat(steps.get(0).getActionType()).isEqualTo("FINAL_ANSWER");
        assertThat(steps.get(0).getStatus()).isEqualTo("FAILED");
        assertThat(steps.get(0).getErrorMessage()).contains("证据不足");
        assertThat(steps.get(1).getActionType()).isEqualTo("QUERY_ARTICLE");
        assertThat(steps.get(2).getActionType()).isEqualTo("FINAL_ANSWER");
        assertThat(steps.get(2).getStatus()).isEqualTo("SUCCESS");
        // 拦截提示进上下文（下一轮决策器看到缺什么）
        AiAgentRun saved = captureRun();
        assertThat(saved.getContextJson()).contains("回答依据不足");
        assertThat(saved.getContextJson()).contains("缓存击穿");
    }

    @Test
    void askUserVerdictConvertsToWaitingUserWithSingleAskStep() {
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE))
                .thenReturn(finalAnswer("这篇的优点是节奏快"));
        when(executor.execute(any(), any(), any()))
                .thenReturn("当前文章分析：\n- 标题：《Redis 缓存》");
        when(verifier.verify(any(), any(), any(), any()))
                .thenReturn(Verdict.askUser("你想让我分析这篇文章的哪个方面？", "用户没说清维度"));

        AgentRunResult result = runtime.run(100L, 200L, "分析一下这篇",
                articleContext("12"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.WAITING_USER);
        assertThat(result.finalAnswer()).isEqualTo("你想让我分析这篇文章的哪个方面？");

        // 只落 QUERY + ASK_USER 两条 step，无伪造 FINAL_ANSWER step
        List<AiAgentStep> steps = captureSteps();
        assertThat(steps).hasSize(2);
        assertThat(steps.get(1).getActionType()).isEqualTo("ASK_USER");
        assertThat(steps.get(1).getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void unresolvedRejectionAtMaxStepsShutsDownConservatively() {
        // 决策器固执地反复 FINAL_ANSWER，验证器恒 NEED_MORE → 5 步全拦 → 到顶保守收尾
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(finalAnswer("这篇不错"));
        when(verifier.verify(any(), any(), any(), any()))
                .thenReturn(Verdict.needMore(Verdict.MissingEvidence.RAG,
                        "还缺站内知识对照"));

        AgentRunResult result = runtime.run(100L, 200L, "这篇和主流写法对比下",
                articleContext("12"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        assertThat(result.usedSteps()).isEqualTo(6);
        // 保守收尾文案（不走 summarize 硬答）
        assertThat(result.finalAnswer()).contains("不足以让我给出可靠结论");
        verify(decider, never()).summarize(any(), any(), any());
    }

    @Test
    void maxStepsWithArticleNeverReadShutsDownConservatively() {
        // 全程只 SEARCH_RAG（从没 QUERY_ARTICLE 目标文章）→ 到顶 S1 保守收尾
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SEARCH_RAG));
        when(executor.execute(any(), any(), any()))
                .thenReturn("站内文章知识检索结果：\n1. 《缓存穿透》...");

        AgentRunResult result = runtime.run(100L, 200L, "分析这篇文章",
                articleContext("12"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        assertThat(result.usedSteps()).isEqualTo(6);
        assertThat(result.finalAnswer()).contains("不足以让我给出可靠结论");
        verify(decider, never()).summarize(any(), any(), any());
    }

    @Test
    void rejectionFollowedBySuccessfulSupplementAtTopUsesSummarize() {
        // 2026-09-10 手测修正：拦后补查成功（材料已在手）→ 到顶不保守，走 summarize 基于新证据收尾
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(finalAnswer("草案一"))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE))
                .thenReturn(finalAnswer("草案二"))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE))
                .thenReturn(finalAnswer("草案三"))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE));
        when(executor.execute(any(), any(), any()))
                .thenReturn("当前文章分析：\n- 标题：《Redis 缓存》");
        when(verifier.verify(any(), any(), any(), any()))
                .thenReturn(Verdict.needMore(Verdict.MissingEvidence.ARTICLE_CONTENT, "缺细节"));

        AgentRunResult result = runtime.run(100L, 200L, "这篇写得怎么样",
                articleContext("12"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        // 最后一次拦截(5) 之后有成功补查(6) → unresolved=false → 不保守 → summarize 收尾
        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        assertThat(result.usedSteps()).isEqualTo(6);
        assertThat(result.finalAnswer()).doesNotContain("不足以让我给出可靠结论");
        verify(decider, org.mockito.Mockito.atLeastOnce()).summarize(any(), any(), any());
    }

    @Test
    void verifierNullKeepsOriginalPath() {
        // 验证器返回 null（默认/未接线）→ 收敛门放行，行为与 V3.10 一致
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(finalAnswer("直接回答"));
        when(verifier.verify(any(), any(), any(), any())).thenReturn(null);

        AgentRunResult result = runtime.run(100L, 200L, "这篇文章怎么样",
                articleContext("12"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        assertThat(result.finalAnswer()).isEqualTo("直接回答");
    }

    private AiAgentRun captureRun() {
        ArgumentCaptor<AiAgentRun> captor = ArgumentCaptor.forClass(AiAgentRun.class);
        verify(runMapper, atLeastOnce()).updateById(captor.capture());
        return captor.getValue();
    }

    private List<AiAgentStep> captureSteps() {
        ArgumentCaptor<AiAgentStep> captor = ArgumentCaptor.forClass(AiAgentStep.class);
        verify(stepMapper, atLeastOnce()).insert(captor.capture());
        return captor.getAllValues();
    }
}
