package com.hailin.blogsystem;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.ai.agent.AgentRunResult;
import com.hailin.blogsystem.ai.agent.AgentStepDecision;
import com.hailin.blogsystem.ai.agent.AgentStepEmitter;
import com.hailin.blogsystem.ai.agent.AgentStepLabelSupport;
import com.hailin.blogsystem.ai.agent.ArticleAgentActionExecutor;
import com.hailin.blogsystem.ai.agent.ArticleAgentRuntime;
import com.hailin.blogsystem.ai.agent.ArticleAgentStepDecider;
import com.hailin.blogsystem.ai.agent.ArticleEvidenceVerifier;
import com.hailin.blogsystem.ai.agent.ArticleSessionAnchorService;
import com.hailin.blogsystem.ai.agent.AgentWriteProposal;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.AiAgentStep;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.mapper.AiAgentStepMapper;
import com.hailin.blogsystem.service.ArticlesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.metadata.Usage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文章域 Agent 核心循环测试（V2.5 / V3.4）。
 * 决策器和执行器全部 mock，验证文章域白名单 / 建议 articleId 透出 / 受控写提案（UPDATE_ARTICLE_TITLE）/ 循环骨架。
 */
class ArticleAgentRuntimeTests {

    private ArticleAgentStepDecider decider;
    private ArticleAgentActionExecutor executor;
    private AiAgentRunMapper runMapper;
    private AiAgentStepMapper stepMapper;
    private ArticlesService articlesService;
    private ArticleSessionAnchorService anchorService;
    private ArticleAgentRuntime runtime;

    private static PageContextDTO articleContext(String articleId) {
        PageContextDTO pageContext = new PageContextDTO();
        pageContext.setPageType("article-detail");
        pageContext.setArticleId(articleId);
        return pageContext;
    }

    private static Articles article(Long id, Long authorId, String title) {
        return article(id, authorId, title, null);
    }

    private static Articles article(Long id, Long authorId, String title, Integer status) {
        Articles article = new Articles();
        article.setId(id);
        article.setAuthorId(authorId);
        article.setTitle(title);
        article.setStatus(status);
        return article;
    }

    @BeforeEach
    void setUp() {
        decider = mock(ArticleAgentStepDecider.class);
        executor = mock(ArticleAgentActionExecutor.class);
        runMapper = mock(AiAgentRunMapper.class);
        stepMapper = mock(AiAgentStepMapper.class);
        articlesService = mock(ArticlesService.class);
        anchorService = mock(ArticleSessionAnchorService.class);

        // MyBatis-Plus ASSIGN_ID 在 mock 下不生效，insert 时手动赋 id
        when(runMapper.insert(any(AiAgentRun.class))).thenAnswer(inv -> {
            AiAgentRun run = inv.getArgument(0);
            run.setId(1L);
            return 1;
        });

        // V3.13：计划落库按影响行数=1 判定成功，默认按命中
        when(runMapper.update(any(), any())).thenReturn(1);

        // V3.11：mock 验证器默认返回 null = 不走收敛门（锁「默认不验证 = 原路径」回归）
        runtime = new ArticleAgentRuntime(
                decider, executor, runMapper, stepMapper, new ObjectMapper(),
                articlesService, anchorService, mock(ArticleEvidenceVerifier.class)
        );
    }

    @Test
    void queryArticleThenFinalAnswerCompletesRun() {
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE)
                        .withInput(Map.of("articleId", "12")))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                        .withInput(Map.of("answer", "这篇文章结构可以，建议补充小标题")));
        when(executor.execute(any(), any(), any()))
                .thenReturn("当前文章分析：\n- 标题：《Redis 缓存设计》\n- 小标题结构：（无小标题）");

        AgentRunResult result = runtime.run(100L, 200L, "帮我看看这篇文章还能怎么改",
                articleContext("12"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        assertThat(result.finalAnswer()).isEqualTo("这篇文章结构可以，建议补充小标题");
        assertThat(result.usedSteps()).isEqualTo(2);

        AiAgentRun saved = captureRun();
        assertThat(saved.getContextJson()).contains("当前文章分析");
    }

    @Test
    void suggestionWithObservationCarriesArticleId() {
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SUGGEST_WORKFLOW)
                        .withInput(Map.of(
                                "workflowType", "OPTIMIZE_ARTICLE",
                                "reason", "这篇文章缺少小标题，建议进入优化流程")));
        when(executor.execute(any(), any(), any()))
                .thenReturn("当前文章分析：\n- 标题：《Redis 缓存设计》");

        AgentRunResult result = runtime.run(100L, 200L, "感觉写得不太好，帮我分析一下",
                articleContext("12"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.WAITING_WORKFLOW_CONFIRM);
        assertThat(result.pendingWorkflowSuggestion()).isNotNull();
        assertThat(result.pendingWorkflowSuggestion().workflowType()).isEqualTo("OPTIMIZE_ARTICLE");
        // 建议携带后端权威 articleId（confirm 定位 + 归属校验用）
        assertThat(result.pendingWorkflowSuggestion().articleId()).isEqualTo("12");

        AiAgentRun saved = captureRun();
        assertThat(saved.getStatus()).isEqualTo("WAITING_WORKFLOW_CONFIRM");
        assertThat(saved.getContextJson()).contains("OPTIMIZE_ARTICLE");
    }

    @Test
    void zeroObservationSuggestionRejectedThenContinues() {
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SUGGEST_WORKFLOW)
                        .withInput(Map.of("workflowType", "OPTIMIZE_ARTICLE")))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                        .withInput(Map.of("answer", "我先看一下你的文章")));

        AgentRunResult result = runtime.run(100L, 200L, "帮我优化这篇文章",
                articleContext("12"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        List<AiAgentStep> steps = captureSteps();
        assertThat(steps.get(0).getActionType()).isEqualTo("SUGGEST_WORKFLOW");
        assertThat(steps.get(0).getStatus()).isEqualTo("FAILED");
        assertThat(steps.get(0).getErrorMessage()).contains("零观察");
    }

    @Test
    void nonOptimizeWorkflowTypeFailsRun() {
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SUGGEST_WORKFLOW)
                        .withInput(Map.of("workflowType", "CREATE_ARTICLE", "reason", "想写文章")));
        when(executor.execute(any(), any(), any())).thenReturn("当前文章分析：\n- 标题：《Redis》");

        AgentRunResult result = runtime.run(100L, 200L, "帮我看看这篇文章",
                articleContext("12"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.FAILED);
        AiAgentRun saved = captureRun();
        assertThat(saved.getErrorMessage()).contains("不在允许范围内");
    }

    @Test
    void suggestWriteWithoutObservationRejectedThenContinues() {
        // V3.4：SUGGEST_WRITE 已进文章域白名单，但零观察提案被拒（没查就提案 = 拍脑袋）→ 循环继续
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SUGGEST_WRITE)
                        .withInput(Map.of("actionType", AgentWriteProposal.TYPE_UPDATE_ARTICLE_TITLE,
                                "newTitle", "Redis 缓存实战")))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                        .withInput(Map.of("answer", "我先看一下你的文章")));

        AgentRunResult result = runtime.run(100L, 200L, "帮我把标题改了",
                articleContext("12"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        List<AiAgentStep> steps = captureSteps();
        assertThat(steps.get(0).getActionType()).isEqualTo("SUGGEST_WRITE");
        assertThat(steps.get(0).getStatus()).isEqualTo("FAILED");
        assertThat(steps.get(0).getErrorMessage()).contains("零观察");
    }

    @Test
    void suggestArticleTitleProposalReachesWaitingConfirm() {
        // V3.4 成功路径：QUERY_ARTICLE 观察 → SUGGEST_WRITE(UPDATE_ARTICLE_TITLE) →
        // 提案端查库拿权威旧标题 → WAITING_WRITE_CONFIRM + pendingWriteAction（articleId 锚 + articleTitle 锚 + newTitle）
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SUGGEST_WRITE)
                        .withInput(Map.of("actionType", AgentWriteProposal.TYPE_UPDATE_ARTICLE_TITLE,
                                "newTitle", "Redis 缓存实战")));
        when(executor.execute(any(), any(), any()))
                .thenReturn("当前文章分析：\n- 标题：《Redis 缓存原理》");
        when(articlesService.getById(12L)).thenReturn(article(12L, 100L, "Redis 缓存原理"));

        AgentRunResult result = runtime.run(100L, 200L, "把标题改成 Redis 缓存实战",
                articleContext("12"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.WAITING_WRITE_CONFIRM);
        assertThat(result.pendingWriteAction()).isNotNull();
        assertThat(result.pendingWriteAction().actionType())
                .isEqualTo(AgentWriteProposal.TYPE_UPDATE_ARTICLE_TITLE);
        // articleId 锚 = 页面上下文；articleTitle 锚 = DB 权威旧标题（不是观察摘录）
        assertThat(result.pendingWriteAction().articleId()).isEqualTo("12");
        assertThat(result.pendingWriteAction().articleTitle()).isEqualTo("Redis 缓存原理");
        assertThat(result.pendingWriteAction().newTitle()).isEqualTo("Redis 缓存实战");

        AiAgentRun saved = captureRun();
        assertThat(saved.getStatus()).isEqualTo("WAITING_WRITE_CONFIRM");
        assertThat(saved.getContextJson()).contains("UPDATE_ARTICLE_TITLE");
        assertThat(saved.getContextJson()).contains("Redis 缓存实战");
    }

    @Test
    void suggestWriteUnknownActionTypeFailsRun() {
        // V3.4：文章域只认 UPDATE_ARTICLE_TITLE——学习域动作/幻觉动作一律 FAILED 终局，绝不回落到任何动作
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SUGGEST_WRITE)
                        .withInput(Map.of("actionType", AgentWriteProposal.TYPE_UPDATE_TASK_DONE,
                                "taskTitle", "缓存击穿")));
        when(executor.execute(any(), any(), any()))
                .thenReturn("当前文章分析：\n- 标题：《Redis 缓存原理》");

        AgentRunResult result = runtime.run(100L, 200L, "把任务勾掉",
                articleContext("12"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.FAILED);
        AiAgentRun saved = captureRun();
        assertThat(saved.getErrorMessage()).contains("不支持的 actionType");
        // 未产生任何写提案（run 直接终局 FAILED，contextJson 无 pendingWriteAction）
        assertThat(saved.getStatus()).isEqualTo("FAILED");
        assertThat(saved.getContextJson()).doesNotContain("pendingWriteAction");
    }

    @Test
    void suggestWriteMissingNewTitleFailsRun() {
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SUGGEST_WRITE)
                        .withInput(Map.of("actionType", AgentWriteProposal.TYPE_UPDATE_ARTICLE_TITLE)));
        when(executor.execute(any(), any(), any()))
                .thenReturn("当前文章分析：\n- 标题：《Redis 缓存原理》");

        AgentRunResult result = runtime.run(100L, 200L, "帮我把标题改一下",
                articleContext("12"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.FAILED);
        AiAgentRun saved = captureRun();
        assertThat(saved.getErrorMessage()).contains("缺少 newTitle");
    }

    @Test
    void suggestWriteWithoutPageContextFailsRun() {
        // V3.8：无页面上下文且会话锚 resolve 为空（无决议目标）→ 提案 FAILED；LLM input 摘录不作数
        when(anchorService.resolveReadable(200L, 100L)).thenReturn(null);
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SUGGEST_WRITE)
                        .withInput(Map.of("actionType", AgentWriteProposal.TYPE_UPDATE_ARTICLE_TITLE,
                                "newTitle", "Redis 缓存实战",
                                "articleId", "999")));
        when(executor.execute(any(), any(), any()))
                .thenReturn("当前文章分析：\n- 标题：《Redis 缓存原理》");

        AgentRunResult result = runtime.run(100L, 200L, "把标题改成 Redis 缓存实战",
                null, com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.FAILED);
        AiAgentRun saved = captureRun();
        assertThat(saved.getErrorMessage()).contains("未决议定位目标");
    }

    @Test
    void homePageSessionAnchorHideProposalReachesWaitingConfirm() {
        // V3.8 场景 A（首页指代）：无页面上下文 + 会话锚 resolve 12 →
        // 首步 QUERY_ARTICLE anchorMode=SESSION_LAST 决议目标 12 → 提案 HIDE_ARTICLE(12) → WAITING_WRITE_CONFIRM
        // 2026-09-10：锚读改可读语义 resolveReadable（返回实体），他人公开文章也可入锚
        when(anchorService.resolveReadable(200L, 100L))
                .thenReturn(article(12L, 100L, "Java 后端面试突围", 1));
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE)
                        .withInput(Map.of("anchorMode", "SESSION_LAST")))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SUGGEST_WRITE)
                        .withInput(Map.of("actionType", AgentWriteProposal.TYPE_HIDE_ARTICLE)));
        when(executor.execute(any(), any(), any()))
                .thenReturn("当前文章分析：\n- 标题：《Java 后端面试突围》");
        when(articlesService.getById(12L)).thenReturn(article(12L, 100L, "Java 后端面试突围", 1));

        AgentRunResult result = runtime.run(100L, 200L, "帮我把刚刚那篇文章隐藏了",
                null, com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.WAITING_WRITE_CONFIRM);
        assertThat(result.pendingWriteAction()).isNotNull();
        assertThat(result.pendingWriteAction().actionType()).isEqualTo(AgentWriteProposal.TYPE_HIDE_ARTICLE);
        // 目标 = 会话锚 12（首页无页面上下文，不可能落到别的文章）
        assertThat(result.pendingWriteAction().articleId()).isEqualTo("12");
        // QUERY_ARTICLE 执行成功 → 会话锚 AGENT_RUN 写点
        verify(anchorService).mark(200L, 12L, ArticleSessionAnchorService.SOURCE_AGENT_RUN);
    }

    @Test
    void sessionLastWinsOverPageContextForProposal() {
        // V3.8 场景 B（错位指代）：站文章 99 详情页说"刚刚那篇"（指会话锚 12）→
        // anchorMode=SESSION_LAST → 提案目标是 12（不是当前页 99，不会被页面上下文抢走）
        when(anchorService.resolveReadable(200L, 100L))
                .thenReturn(article(12L, 100L, "Java 后端面试突围", 1));
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE)
                        .withInput(Map.of("anchorMode", "SESSION_LAST")))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SUGGEST_WRITE)
                        .withInput(Map.of("actionType", AgentWriteProposal.TYPE_HIDE_ARTICLE)));
        when(executor.execute(any(), any(), any()))
                .thenReturn("当前文章分析：\n- 标题：《Java 后端面试突围》");
        when(articlesService.getById(12L)).thenReturn(article(12L, 100L, "Java 后端面试突围", 1));

        AgentRunResult result = runtime.run(100L, 200L, "帮我把刚刚那篇隐藏了",
                articleContext("99"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.WAITING_WRITE_CONFIRM);
        // 提案目标是会话锚文章 12，不是当前页文章 99
        assertThat(result.pendingWriteAction().articleId()).isEqualTo("12");
    }

    @Test
    void pageContextArticleIsDefaultTargetWhenNoAnchorMode() {
        // V3.8 回归：详情页无 anchorMode（缺省 CURRENT_PAGE）→ 目标 = 当前页文章，提案针对当前页
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SUGGEST_WRITE)
                        .withInput(Map.of("actionType", AgentWriteProposal.TYPE_HIDE_ARTICLE)));
        when(executor.execute(any(), any(), any()))
                .thenReturn("当前文章分析：\n- 标题：《Redis 缓存原理》");
        when(articlesService.getById(12L)).thenReturn(article(12L, 100L, "Redis 缓存原理", 1));

        AgentRunResult result = runtime.run(100L, 200L, "把这篇隐藏了",
                articleContext("12"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.WAITING_WRITE_CONFIRM);
        assertThat(result.pendingWriteAction().articleId()).isEqualTo("12");
        verify(anchorService).mark(200L, 12L, ArticleSessionAnchorService.SOURCE_AGENT_RUN);
    }

    @Test
    void suggestWriteOtherUsersArticleEndsTerminally() {
        // V3.4：归属失败 → 终局失败（COMPLETED + 友好文案，不误写、不继续循环）
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SUGGEST_WRITE)
                        .withInput(Map.of("actionType", AgentWriteProposal.TYPE_UPDATE_ARTICLE_TITLE,
                                "newTitle", "Redis 缓存实战")));
        when(executor.execute(any(), any(), any()))
                .thenReturn("当前文章分析：\n- 标题：《别人的文章》");
        when(articlesService.getById(12L)).thenReturn(article(12L, 101L, "别人的文章"));

        AgentRunResult result = runtime.run(100L, 200L, "把标题改了",
                articleContext("12"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        assertThat(result.finalAnswer()).contains("不属于你");
        assertThat(result.usedSteps()).isEqualTo(2);
        List<AiAgentStep> steps = captureSteps();
        assertThat(steps.get(1).getActionType()).isEqualTo("SUGGEST_WRITE");
        assertThat(steps.get(1).getStatus()).isEqualTo("FAILED");
    }

    @Test
    void suggestWriteSameTitleRejectedThenContinues() {
        // V3.4：新标题 == 当前标题（无变化）→ FAILED step + observation，不弹卡，循环继续由 LLM 告知
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SUGGEST_WRITE)
                        .withInput(Map.of("actionType", AgentWriteProposal.TYPE_UPDATE_ARTICLE_TITLE,
                                "newTitle", "redis 缓存原理")))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                        .withInput(Map.of("answer", "标题没变化，不需要修改")));
        when(executor.execute(any(), any(), any()))
                .thenReturn("当前文章分析：\n- 标题：《Redis 缓存原理》");
        when(articlesService.getById(12L)).thenReturn(article(12L, 100L, "Redis 缓存原理"));

        AgentRunResult result = runtime.run(100L, 200L, "把标题改一下",
                articleContext("12"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        assertThat(result.finalAnswer()).isEqualTo("标题没变化，不需要修改");
        List<AiAgentStep> steps = captureSteps();
        assertThat(steps.get(1).getActionType()).isEqualTo("SUGGEST_WRITE");
        assertThat(steps.get(1).getStatus()).isEqualTo("FAILED");
        assertThat(steps.get(1).getErrorMessage()).contains("新标题与原标题相同");
    }

    @Test
    void emitterReceivesStepEventsInOrder() {
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                        .withInput(Map.of("answer", "建议补充小标题")));
        when(executor.execute(any(), any(), any())).thenReturn("当前文章分析：\n- 标题：《Redis》");

        List<String[]> events = new java.util.ArrayList<>();
        runtime.run(100L, 200L, "帮我看看这篇文章",
                articleContext("12"), (stepNo, actionType, status, message, thoughtSummary) ->
                        events.add(new String[]{String.valueOf(stepNo), actionType, status, message}));

        assertThat(events).hasSize(3);
        assertThat(events.get(0)[1]).isEqualTo("QUERY_ARTICLE");
        assertThat(events.get(0)[2]).isEqualTo("RUNNING");
        assertThat(events.get(1)[2]).isEqualTo("SUCCESS");
        assertThat(events.get(2)[1]).isEqualTo("FINAL_ANSWER");
        assertThat(events.get(2)[2]).isEqualTo("SUCCESS");
        // 文章域 FINAL_ANSWER 文案
        assertThat(events.get(2)[3]).isEqualTo("已生成最终回答");
    }

    @Test
    void maxStepsReachedProducesArticleSummary() {
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE));
        when(executor.execute(any(), any(), any())).thenReturn("第 X 条观察数据");

        AgentRunResult result = runtime.run(100L, 200L, "帮我看看这篇文章",
                articleContext("12"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        assertThat(result.usedSteps()).isEqualTo(6);
        assertThat(result.finalAnswer()).contains("根据对这篇文章的分析");
    }

    @Test
    void createRunCancelsStalePendingSuggestions() {
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                        .withInput(Map.of("answer", "好的")));

        runtime.run(100L, 200L, "继续",
                articleContext("12"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        ArgumentCaptor<AiAgentRun> patchCaptor = ArgumentCaptor.forClass(AiAgentRun.class);
        verify(runMapper).update(patchCaptor.capture(), any());
        assertThat(patchCaptor.getValue().getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void terminalFailureEndsRunImmediately() {
        // V2.5 终局失败：文章归属校验失败 → 失败 step 落库后直接结束，
        // 不再走下一轮决策（老大验收：第 1 步结束即可）
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE));
        when(executor.execute(any(), any(), any()))
                .thenThrow(new com.hailin.blogsystem.ai.agent.ArticleNotOwnedException(
                        "这篇文章不是你的，无法为你分析或优化。"));

        AgentRunResult result = runtime.run(100L, 200L, "帮我看看这篇文章",
                articleContext("12"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        // 后端直接生成友好文案（不走 LLM）
        assertThat(result.finalAnswer()).isEqualTo("这篇文章不是你的，无法为你分析或优化。");
        // 只执行了 1 步（QUERY_ARTICLE FAILED），没有 FINAL_ANSWER 步骤
        assertThat(result.usedSteps()).isEqualTo(1);
        List<AiAgentStep> steps = captureSteps();
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getActionType()).isEqualTo("QUERY_ARTICLE");
        assertThat(steps.get(0).getStatus()).isEqualTo("FAILED");
        // 决策器只被调用一次（循环已终止）
        org.mockito.Mockito.verify(decider, org.mockito.Mockito.times(1)).decide(any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void executorFailureContinuesLoopAndFailsStep() {
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                        .withInput(Map.of("answer", "这篇文章不是你的，无法分析")));
        when(executor.execute(any(), any(), any())).thenThrow(new IllegalArgumentException("这篇文章不是你的，无法分析或建议优化。"));

        AgentRunResult result = runtime.run(100L, 200L, "帮我看看这篇文章",
                articleContext("12"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        assertThat(result.finalAnswer()).isEqualTo("这篇文章不是你的，无法分析");

        List<AiAgentStep> steps = captureSteps();
        assertThat(steps.get(0).getActionType()).isEqualTo("QUERY_ARTICLE");
        assertThat(steps.get(0).getStatus()).isEqualTo("FAILED");
        AiAgentRun saved = captureRun();
        assertThat(saved.getContextJson()).contains("动作执行失败");
    }

    @Test
    void suggestHideArticleProposalReachesWaitingConfirm() {
        // V3.7：已发布文章提隐藏 → 提案（前置 PUBLISHED 满足）→ WAITING_WRITE_CONFIRM
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SUGGEST_WRITE)
                        .withInput(Map.of("actionType", "HIDE_ARTICLE")));
        when(executor.execute(any(), any(), any()))
                .thenReturn("当前文章分析：\n- 标题：《Redis 缓存原理》\n- 状态：已发布");
        when(articlesService.getById(12L))
                .thenReturn(article(12L, 100L, "Redis 缓存原理", 1));

        AgentRunResult result = runtime.run(100L, 200L, "把这篇隐藏了",
                articleContext("12"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.WAITING_WRITE_CONFIRM);
        assertThat(result.pendingWriteAction().actionType()).isEqualTo("HIDE_ARTICLE");
        assertThat(result.pendingWriteAction().articleId()).isEqualTo("12");
        assertThat(result.pendingWriteAction().articleTitle()).isEqualTo("Redis 缓存原理");
        AiAgentRun saved = captureRun();
        assertThat(saved.getContextJson()).contains("HIDE_ARTICLE");
    }

    @Test
    void suggestPublishArticleProposalReachesWaitingConfirm() {
        // V3.7：隐藏中文章提公开 → 提案（前置 HIDDEN 满足）
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SUGGEST_WRITE)
                        .withInput(Map.of("actionType", "PUBLISH_ARTICLE")));
        when(executor.execute(any(), any(), any()))
                .thenReturn("当前文章分析：\n- 标题：《Redis 缓存原理》\n- 状态：已隐藏");
        when(articlesService.getById(12L))
                .thenReturn(article(12L, 100L, "Redis 缓存原理", 2));

        AgentRunResult result = runtime.run(100L, 200L, "把这篇公开了",
                articleContext("12"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.WAITING_WRITE_CONFIRM);
        assertThat(result.pendingWriteAction().actionType()).isEqualTo("PUBLISH_ARTICLE");
    }

    @Test
    void suggestVisibilityChangeOnDraftFailsRun() {
        // V3.7：草稿拒绝——Agent 不从详情页把草稿发布/隐藏（编辑器人工闸保留）
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SUGGEST_WRITE)
                        .withInput(Map.of("actionType", "HIDE_ARTICLE")));
        when(executor.execute(any(), any(), any()))
                .thenReturn("当前文章分析：\n- 标题：《未发布的草稿》");
        when(articlesService.getById(12L))
                .thenReturn(article(12L, 100L, "未发布的草稿", 0));

        AgentRunResult result = runtime.run(100L, 200L, "把这篇文章隐藏",
                articleContext("12"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.FAILED);
        AiAgentRun saved = captureRun();
        assertThat(saved.getErrorMessage()).contains("还未发布");
    }

    @Test
    void suggestHideAlreadyHiddenArticleRejectedThenContinues() {
        // V3.7：已是目标状态（已隐藏再提隐藏）→ FAILED step 不弹卡，循环继续由 LLM 告知
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SUGGEST_WRITE)
                        .withInput(Map.of("actionType", "HIDE_ARTICLE")))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                        .withInput(Map.of("answer", "这篇文章已经是隐藏状态了")));
        when(executor.execute(any(), any(), any()))
                .thenReturn("当前文章分析：\n- 标题：《Redis 缓存原理》\n- 状态：已隐藏");
        when(articlesService.getById(12L))
                .thenReturn(article(12L, 100L, "Redis 缓存原理", 2));

        AgentRunResult result = runtime.run(100L, 200L, "把这篇隐藏了",
                articleContext("12"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        assertThat(result.finalAnswer()).isEqualTo("这篇文章已经是隐藏状态了");
        List<AiAgentStep> steps = captureSteps();
        assertThat(steps.get(1).getActionType()).isEqualTo("SUGGEST_WRITE");
        assertThat(steps.get(1).getStatus()).isEqualTo("FAILED");
        assertThat(steps.get(1).getErrorMessage()).contains("已处于隐藏状态");
    }

    // ==================== V3.12 结论锚写点 A ====================

    @Test
    void finalAnswerAfterReadingArticleWritesConclusionAnchor() {
        // 主路径：读过文章 + FINAL_ANSWER → 写结论锚（存 finalAnswer 原文）
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                        .withInput(Map.of("answer", "建议补充缓存击穿原理")));
        when(executor.execute(any(), any(), any())).thenReturn("当前文章分析：\n- 标题：《Redis 缓存》");
        when(articlesService.getById(12L)).thenReturn(article(12L, 100L, "Redis 缓存", 1));

        runtime.run(100L, 200L, "分析这篇文章", articleContext("12"),
                com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        verify(anchorService).markConclusion(
                200L, 12L, "建议补充缓存击穿原理", 1L,
                ArticleSessionAnchorService.CONCLUSION_SOURCE_FINAL_ANSWER);
    }

    @Test
    void finalAnswerWithoutReadingArticleSkipsAnchor() {
        // 证据边界：目标文章已决议但没成功读过 → 不把无证据回答存成结论锚。
        // （V3.11 R1 正常会拦，但验证器 fail-open 时本条件是唯一防线）
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SEARCH_RAG)
                        .withInput(Map.of("keyword", "缓存")))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                        .withInput(Map.of("answer", "这篇写得不错")));
        when(executor.execute(any(), any(), any())).thenReturn("站内文章知识检索结果：...");
        when(articlesService.getById(12L)).thenReturn(article(12L, 100L, "Redis 缓存", 1));

        runtime.run(100L, 200L, "分析这篇文章", articleContext("12"),
                com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        verify(anchorService, never()).markConclusion(any(), any(), any(), any(), any());
    }

    @Test
    void anchorWriteFailureDoesNotBreakAnswer() {
        // fail-open：写锚抛异常不能影响用户看到回答
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                        .withInput(Map.of("answer", "建议补充示例")));
        when(executor.execute(any(), any(), any())).thenReturn("当前文章分析：...");
        when(articlesService.getById(12L)).thenReturn(article(12L, 100L, "Redis 缓存", 1));
        org.mockito.Mockito.doThrow(new RuntimeException("DB down"))
                .when(anchorService).markConclusion(any(), any(), any(), any(), any());

        AgentRunResult result = runtime.run(100L, 200L, "分析这篇文章", articleContext("12"),
                com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        assertThat(result.finalAnswer()).isEqualTo("建议补充示例");
    }

    // ==================== V3.13 Plan Preview ====================

    @Test
    void firstStepPlanIsPersistedBySingleColumnUpdateAndEmitted() {
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(new AgentStepDecision(
                        AgentStepActionType.QUERY_ARTICLE,
                        Map.of("articleId", "12"),
                        "先读文章看结构",
                        List.of("先看整体结构", "再检查缓存击穿那一节")))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                        .withInput(Map.of("answer", "整体结构不错")));
        when(executor.execute(any(), any(), any())).thenReturn("当前文章分析：\n- 标题：《Redis 缓存设计》");

        List<List<String>> emitted = new ArrayList<>();
        runtime.run(100L, 200L, "先看整体结构，再检查缓存击穿那一节",
                articleContext("12"), planCapturingEmitter(emitted));

        assertThat(emitted).hasSize(1);
        assertThat(emitted.get(0)).containsExactly("先看整体结构", "再检查缓存击穿那一节");

        // 落库走**单列更新**（不复用 updateById——否则计划写失败会与 run 状态写失败混成一个故障）
        ArgumentCaptor<UpdateWrapper<AiAgentRun>> captor =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(runMapper, atLeastOnce()).update(any(), captor.capture());
        assertThat(captor.getAllValues())
                .anyMatch(w -> w.getSqlSet() != null && w.getSqlSet().contains("plan_json"));
    }

    @Test
    void planNeverEntersObservations() {
        // 关键隔离锁：计划一旦进 observations，Verifier 会把它当证据
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(new AgentStepDecision(
                        AgentStepActionType.QUERY_ARTICLE,
                        Map.of("articleId", "12"),
                        null,
                        List.of("先看整体结构", "再检查缓存击穿那一节")))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                        .withInput(Map.of("answer", "整体结构不错")));
        when(executor.execute(any(), any(), any())).thenReturn("当前文章分析：\n- 标题：《Redis 缓存设计》");

        runtime.run(100L, 200L, "先看整体结构，再检查缓存击穿那一节",
                articleContext("12"), planCapturingEmitter(new ArrayList<>()));

        AiAgentRun saved = captureRun();
        assertThat(saved.getContextJson()).doesNotContain("先看整体结构");
        assertThat(saved.getContextJson()).doesNotContain("缓存击穿");
    }

    @Test
    void invalidPlanIsDroppedWithoutEmitting() {
        // 1 项不是多目标 → 整条丢弃（零修补），不发事件、不影响流程
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(new AgentStepDecision(
                        AgentStepActionType.QUERY_ARTICLE,
                        Map.of("articleId", "12"),
                        null,
                        List.of("先看整体结构")))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                        .withInput(Map.of("answer", "整体结构不错")));
        when(executor.execute(any(), any(), any())).thenReturn("当前文章分析");

        List<List<String>> emitted = new ArrayList<>();
        AgentRunResult result = runtime.run(100L, 200L, "看看这篇文章",
                articleContext("12"), planCapturingEmitter(emitted));

        assertThat(emitted).isEmpty();
        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
    }

    @Test
    void planSkippedWhenFirstStepIsTerminal() {
        // 首步即终态/需澄清 → 没有实际执行基线，计划无法用于观测，且可能在澄清前误导用户
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(new AgentStepDecision(
                        AgentStepActionType.ASK_USER,
                        Map.of("question", "你指的是哪篇？"),
                        null,
                        List.of("先看整体结构", "再给建议")));

        List<List<String>> emitted = new ArrayList<>();
        runtime.run(100L, 200L, "这篇文章怎么样", articleContext("12"),
                planCapturingEmitter(emitted));

        assertThat(emitted).isEmpty();
    }

    @Test
    void planFromNonFirstStepIsIgnored() {
        // 只在首步采集；第二步即使带 plan 也忽略
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE)
                        .withInput(Map.of("articleId", "12")))
                .thenReturn(new AgentStepDecision(
                        AgentStepActionType.FINAL_ANSWER,
                        Map.of("answer", "好"),
                        null,
                        List.of("第二步才给的计划", "第二项")));
        when(executor.execute(any(), any(), any())).thenReturn("当前文章分析");

        List<List<String>> emitted = new ArrayList<>();
        runtime.run(100L, 200L, "看看", articleContext("12"), planCapturingEmitter(emitted));

        assertThat(emitted).isEmpty();
    }

    @Test
    void planNotEmittedWhenPersistMissesRunRow() {
        // 落库影响行数≠1 → 只记日志不发事件（防「实时有计划、库里没有」分叉），且不影响流程
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(new AgentStepDecision(
                        AgentStepActionType.QUERY_ARTICLE,
                        Map.of("articleId", "12"),
                        null,
                        List.of("先看整体结构", "再给建议")))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                        .withInput(Map.of("answer", "结论")));
        when(executor.execute(any(), any(), any())).thenReturn("当前文章分析");
        when(runMapper.update(any(), any())).thenReturn(0);

        List<List<String>> emitted = new ArrayList<>();
        AgentRunResult result = runtime.run(100L, 200L, "先看整体结构，再给建议",
                articleContext("12"), planCapturingEmitter(emitted));

        assertThat(emitted).isEmpty();
        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
    }

    // ==================== V4 规则级重复拦截 ====================

    @Test
    void duplicatedQueryIsRejectedWithoutExecuting() {
        // 同一动作 + 同一参数第二次出现 → 不执行、落 SKIPPED step、观察带可执行的下一步
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_MEMORY)
                        .withInput(Map.of("question", "写作偏好")))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_MEMORY)
                        .withInput(Map.of("question", "写作偏好")))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                        .withInput(Map.of("answer", "结论")));
        when(executor.execute(any(), any(), any())).thenReturn("记忆摘要：用户喜欢简洁 UI");

        // 首次决策时库里还没有该查询（放行），第二次时第 1 步已成功执行过同参查询（拦截）
        AiAgentStep executed = new AiAgentStep();
        executed.setStepNo(1);
        when(stepMapper.selectOne(any())).thenReturn(null, executed);

        List<String> emitted = new ArrayList<>();
        AgentRunResult result = runtime.run(100L, 200L, "先看整体结构，再结合写作偏好给建议",
                articleContext("12"), (stepNo, actionType, status, message, thoughtSummary) ->
                        emitted.add(stepNo + ":" + status + ":" + message));

        // 核心：第二次没有真的执行（省掉一次无意义调用）
        verify(executor, times(1)).execute(any(), any(), any());
        // 拦截仍落 step（消耗步数，防绕过 maxSteps 死循环）
        ArgumentCaptor<AiAgentStep> stepCaptor = ArgumentCaptor.forClass(AiAgentStep.class);
        verify(stepMapper, atLeastOnce()).insert(stepCaptor.capture());
        assertThat(stepCaptor.getAllValues())
                .anyMatch(step -> step.getErrorMessage() != null
                        && step.getErrorMessage().startsWith(AgentStepLabelSupport.DUPLICATE_QUERY_SKIP_PREFIX)
                        && "SKIPPED".equals(step.getStatus()));
        assertThat(emitted).contains("2:SKIPPED:" + AgentStepLabelSupport.DUPLICATE_QUERY_SKIP_MESSAGE);
        // 提示要给出下一步能做什么，而不是单纯禁止
        assertThat(captureRun().getContextJson()).contains("重复了第 1 步");
        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
    }

    @Test
    void duplicateCheckRunsBeforeEachActionButNotForTerminal() {
        // 重复检查在只读动作执行前各跑一次；终态动作（FINAL_ANSWER）不跑——
        // 终态不可能"重复执行"，多查一次是纯浪费。
        //
        // 注意：判据里的「动作 + 参数 + 曾成功」由 SQL WHERE 完成，纯单测环境无法回放
        // （mock 只能验证"检查发生过"）。参数匹配的正确性靠手测覆盖。
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE)
                        .withInput(Map.of("articleId", "12")))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                        .withInput(Map.of("answer", "结论")));
        when(executor.execute(any(), any(), any())).thenReturn("当前文章分析");
        when(stepMapper.selectOne(any())).thenReturn(null);

        AgentRunResult result = runtime.run(100L, 200L, "看看这篇文章", articleContext("12"),
                planCapturingEmitter(new ArrayList<>()));

        verify(stepMapper, times(1)).selectOne(any());
        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
    }

    @Test
    void observationsCarrySourceTagWithoutInternalIds() {
        // 治本契约：模型必须看得见「做过什么动作、用什么参数」——
        // 否则它无法确认某段结果是不是「我要查的那个查询」的产物，只能用重复查询试探。
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE)
                        .withInput(Map.of("articleId", "2085208555163787287", "focus", "缓存击穿")))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                        .withInput(Map.of("answer", "结论")));
        when(executor.execute(any(), any(), any())).thenReturn("【聚焦片段】…");
        when(stepMapper.selectOne(any())).thenReturn(null);

        runtime.run(100L, 200L, "检查缓存击穿那一节",
                articleContext("2085208555163787287"), planCapturingEmitter(new ArrayList<>()));

        String contextJson = captureRun().getContextJson();
        assertThat(contextJson).contains("[QUERY_ARTICLE focus=缓存击穿]");
        // 内部标识不进观察（模型不需要，按规则也不该看到）
        assertThat(contextJson).doesNotContain("2085208555163787287");
    }

    /** V3.13：捕获 emitPlan 的 emitter（步骤事件忽略）。 */
    private AgentStepEmitter planCapturingEmitter(List<List<String>> sink) {
        return new AgentStepEmitter() {
            @Override
            public void emit(int stepNo, String actionType, String status,
                             String message, String thoughtSummary) {
            }

            @Override
            public void emitPlan(Long agentRunId, List<String> plan) {
                sink.add(plan);
            }
        };
    }

    // ==================== token 统计（2026-09-17） ====================

    /**
     * 决策器消耗的 token 要同时落到 **run 级**（各步之和）和 **step 级**（谁花的算谁的）。
     *
     * 真实 LlmAgentStepDecider 会把 usage 写进 decide 的第 5 个出参，这里用 thenAnswer 模拟。
     * usage 对象在 stub 之前构造好——在 answer 里调 mock(...) 会打断 Mockito 的 stubbing 状态机。
     */
    @Test
    void tokensAreAccumulatedIntoRunAndStep() {
        Usage firstStepUsage = mockUsage(100, 10, 110);
        Usage secondStepUsage = mockUsage(200, 20, 220);

        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenAnswer(inv -> {
                    inv.getArgument(4, TokenUsageAccumulator.class).add(firstStepUsage);
                    return AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE)
                            .withInput(Map.of("articleId", "12"));
                })
                .thenAnswer(inv -> {
                    inv.getArgument(4, TokenUsageAccumulator.class).add(secondStepUsage);
                    return AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                            .withInput(Map.of("answer", "看完了"));
                });
        when(executor.execute(any(), any(), any())).thenReturn("当前文章分析：\n- 标题：《Redis》");

        AgentRunResult result = runtime.run(100L, 200L, "帮我看看这篇文章",
                articleContext("12"), AgentStepEmitter.noop());

        assertThat(result.totalTokens())
                .as("AgentRunResult 要带回 run 的 token——聊天链路拿它写 ai_messages.token_count")
                .isEqualTo(330);

        // run 级 = 各步之和
        AiAgentRun saved = captureRun();
        assertThat(saved.getTotalTokens()).isEqualTo(330);
        assertThat(saved.getInputTokens()).isEqualTo(300);
        assertThat(saved.getOutputTokens()).isEqualTo(30);

        // step 级 = 各步自己的用量
        List<AiAgentStep> steps = captureSteps();
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).getInputTokens()).isEqualTo(100);
        assertThat(steps.get(0).getOutputTokens()).isEqualTo(10);
        assertThat(steps.get(1).getInputTokens()).isEqualTo(200);
        assertThat(steps.get(1).getOutputTokens()).isEqualTo(20);
    }

    /**
     * finally 兜底：token 还要走一次**单列更新**落库。
     *
     * run 的出口有 8 个，各自的 updateById 顺带写入；兜底这一道防的是
     * 「某个出口走的是不带全字段的单列 UPDATE」——那种情况下 token 会丢。
     */
    @Test
    void tokensArePersistedByDedicatedSingleColumnUpdate() {
        Usage singleStepUsage = mockUsage(100, 10, 110);

        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenAnswer(inv -> {
                    inv.getArgument(4, TokenUsageAccumulator.class).add(singleStepUsage);
                    return AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                            .withInput(Map.of("answer", "好的"));
                });

        runtime.run(100L, 200L, "好的", articleContext("12"), AgentStepEmitter.noop());

        ArgumentCaptor<UpdateWrapper<AiAgentRun>> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(runMapper, atLeastOnce()).update(any(), captor.capture());
        assertThat(captor.getAllValues())
                .anyMatch(w -> w.getSqlSet() != null && w.getSqlSet().contains("total_tokens"));
    }

    private static Usage mockUsage(int prompt, int completion, int total) {
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(prompt);
        when(usage.getCompletionTokens()).thenReturn(completion);
        when(usage.getTotalTokens()).thenReturn(total);
        return usage;
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
