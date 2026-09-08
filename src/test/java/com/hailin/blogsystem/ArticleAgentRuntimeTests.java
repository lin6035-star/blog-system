package com.hailin.blogsystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.agent.AgentRunResult;
import com.hailin.blogsystem.ai.agent.AgentStepDecision;
import com.hailin.blogsystem.ai.agent.ArticleAgentActionExecutor;
import com.hailin.blogsystem.ai.agent.ArticleAgentRuntime;
import com.hailin.blogsystem.ai.agent.ArticleAgentStepDecider;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
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

        runtime = new ArticleAgentRuntime(
                decider, executor, runMapper, stepMapper, new ObjectMapper(),
                articlesService, anchorService
        );
    }

    @Test
    void queryArticleThenFinalAnswerCompletesRun() {
        when(decider.decide(any(), any(), anyInt(), anyInt()))
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
        when(decider.decide(any(), any(), anyInt(), anyInt()))
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
        when(decider.decide(any(), any(), anyInt(), anyInt()))
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
        when(decider.decide(any(), any(), anyInt(), anyInt()))
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
        when(decider.decide(any(), any(), anyInt(), anyInt()))
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
        when(decider.decide(any(), any(), anyInt(), anyInt()))
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
        when(decider.decide(any(), any(), anyInt(), anyInt()))
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
        when(decider.decide(any(), any(), anyInt(), anyInt()))
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
        when(anchorService.resolve(200L, 100L)).thenReturn(null);
        when(decider.decide(any(), any(), anyInt(), anyInt()))
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
        when(anchorService.resolve(200L, 100L))
                .thenReturn(new ArticleSessionAnchorService.ArticleAnchor(12L, "Java 后端面试突围"));
        when(decider.decide(any(), any(), anyInt(), anyInt()))
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
        when(anchorService.resolve(200L, 100L))
                .thenReturn(new ArticleSessionAnchorService.ArticleAnchor(12L, "Java 后端面试突围"));
        when(decider.decide(any(), any(), anyInt(), anyInt()))
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
        when(decider.decide(any(), any(), anyInt(), anyInt()))
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
        when(decider.decide(any(), any(), anyInt(), anyInt()))
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
        when(decider.decide(any(), any(), anyInt(), anyInt()))
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
        when(decider.decide(any(), any(), anyInt(), anyInt()))
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
        when(decider.decide(any(), any(), anyInt(), anyInt()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE));
        when(executor.execute(any(), any(), any())).thenReturn("第 X 条观察数据");

        AgentRunResult result = runtime.run(100L, 200L, "帮我看看这篇文章",
                articleContext("12"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        assertThat(result.usedSteps()).isEqualTo(5);
        assertThat(result.finalAnswer()).contains("根据对这篇文章的分析");
    }

    @Test
    void createRunCancelsStalePendingSuggestions() {
        when(decider.decide(any(), any(), anyInt(), anyInt()))
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
        when(decider.decide(any(), any(), anyInt(), anyInt()))
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
        org.mockito.Mockito.verify(decider, org.mockito.Mockito.times(1)).decide(any(), any(), anyInt(), anyInt());
    }

    @Test
    void executorFailureContinuesLoopAndFailsStep() {
        when(decider.decide(any(), any(), anyInt(), anyInt()))
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
        when(decider.decide(any(), any(), anyInt(), anyInt()))
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
        when(decider.decide(any(), any(), anyInt(), anyInt()))
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
        when(decider.decide(any(), any(), anyInt(), anyInt()))
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
        when(decider.decide(any(), any(), anyInt(), anyInt()))
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
