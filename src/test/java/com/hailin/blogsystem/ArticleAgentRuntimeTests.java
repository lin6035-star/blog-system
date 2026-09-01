package com.hailin.blogsystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.agent.AgentRunResult;
import com.hailin.blogsystem.ai.agent.AgentStepDecision;
import com.hailin.blogsystem.ai.agent.ArticleAgentActionExecutor;
import com.hailin.blogsystem.ai.agent.ArticleAgentRuntime;
import com.hailin.blogsystem.ai.agent.ArticleAgentStepDecider;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.AiAgentStep;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.mapper.AiAgentStepMapper;
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
 * 文章域 Agent 核心循环测试（V2.5）。
 * 决策器和执行器全部 mock，验证文章域白名单 / 建议 articleId 透出 / 禁写提案 / 循环骨架。
 */
class ArticleAgentRuntimeTests {

    private ArticleAgentStepDecider decider;
    private ArticleAgentActionExecutor executor;
    private AiAgentRunMapper runMapper;
    private AiAgentStepMapper stepMapper;
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

        // MyBatis-Plus ASSIGN_ID 在 mock 下不生效，insert 时手动赋 id
        when(runMapper.insert(any(AiAgentRun.class))).thenAnswer(inv -> {
            AiAgentRun run = inv.getArgument(0);
            run.setId(1L);
            return 1;
        });

        runtime = new ArticleAgentRuntime(
                decider, executor, runMapper, stepMapper, new ObjectMapper()
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
    void suggestWriteRejectedByWhitelist() {
        // 文章域白名单无 SUGGEST_WRITE：非法动作直接 FAILED（文章域禁写提案）
        when(decider.decide(any(), any(), anyInt(), anyInt()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SUGGEST_WRITE)
                        .withInput(Map.of("taskTitle", "缓存击穿")));

        AgentRunResult result = runtime.run(100L, 200L, "帮我把任务勾掉",
                articleContext("12"), com.hailin.blogsystem.ai.agent.AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.FAILED);
        AiAgentRun saved = captureRun();
        assertThat(saved.getErrorMessage()).contains("非法动作");
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
                articleContext("12"), (stepNo, actionType, status, message) ->
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
