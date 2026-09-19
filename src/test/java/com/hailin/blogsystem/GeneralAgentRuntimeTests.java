package com.hailin.blogsystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.agent.AgentRunResult;
import com.hailin.blogsystem.ai.agent.AgentStepDecision;
import com.hailin.blogsystem.ai.agent.AgentStepEmitter;
import com.hailin.blogsystem.ai.agent.GeneralAgentActionExecutor;
import com.hailin.blogsystem.ai.agent.GeneralAgentRuntime;
import com.hailin.blogsystem.ai.agent.GeneralAgentStepDecider;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.AiAgentStep;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通用域 Agent 核心循环测试（V3 通用思考模式）。
 * 决策器和执行器全部 mock，验证通用域白名单 / maxSteps=3 / 直接回答优先 / 循环骨架。
 */
class GeneralAgentRuntimeTests {

    private GeneralAgentStepDecider decider;
    private GeneralAgentActionExecutor executor;
    private AiAgentRunMapper runMapper;
    private AiAgentStepMapper stepMapper;
    private GeneralAgentRuntime runtime;

    @BeforeEach
    void setUp() {
        decider = mock(GeneralAgentStepDecider.class);
        executor = mock(GeneralAgentActionExecutor.class);
        runMapper = mock(AiAgentRunMapper.class);
        stepMapper = mock(AiAgentStepMapper.class);

        // MyBatis-Plus ASSIGN_ID 在 mock 下不生效，insert 时手动赋 id
        when(runMapper.insert(any(AiAgentRun.class))).thenAnswer(inv -> {
            AiAgentRun run = inv.getArgument(0);
            run.setId(1L);
            return 1;
        });

        runtime = new GeneralAgentRuntime(
                decider, executor, runMapper, stepMapper, new ObjectMapper()
        );
    }

    @Test
    void queryMemoryThenFinalAnswerCompletesRun() {
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_MEMORY)
                        .withInput(Map.of("question", "最近学习进展")))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                        .withInput(Map.of("answer", "结合你的记忆，建议继续深入缓存部分")));
        when(executor.execute(any(), any(), any()))
                .thenReturn("记忆摘要：\n- 语义记忆(USER_PREFERENCE)：正在学 Redis 缓存");

        AgentRunResult result = runtime.run(100L, 200L, "你觉得我现在这套方案还有什么问题",
                null, AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        assertThat(result.finalAnswer()).isEqualTo("结合你的记忆，建议继续深入缓存部分");
        assertThat(result.usedSteps()).isEqualTo(2);

        AiAgentRun saved = captureRun();
        assertThat(saved.getContextJson()).contains("记忆摘要");
    }

    @Test
    void directFinalAnswerWithoutAnyQuery() {
        // 「能不查就不查」：已有信息足够时直接终态回答，执行器零调用
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                        .withInput(Map.of("answer", "这个是纯概念问题，直接回答")));

        AgentRunResult result = runtime.run(100L, 200L, "什么是缓存穿透",
                null, AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        assertThat(result.finalAnswer()).isEqualTo("这个是纯概念问题，直接回答");
        assertThat(result.usedSteps()).isEqualTo(1);
        verify(executor, never()).execute(any(), any(), any());
    }

    @Test
    void searchRagAsEvidenceAfterMemory() {
        // 记忆为主、RAG 补证据：QUERY_MEMORY → SEARCH_RAG → FINAL_ANSWER
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_MEMORY))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SEARCH_RAG)
                        .withInput(Map.of("keyword", "缓存设计")))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                        .withInput(Map.of("answer", "结合记忆和站内文章，结论如下")));
        when(executor.execute(any(), any(), any()))
                .thenReturn("记忆摘要：\n- 语义记忆(USER_PREFERENCE)：正在学 Redis")
                .thenReturn("站内文章知识检索结果（hybrid）：\n1. 《Redis缓存设计》(1)：缓存击穿防护");

        AgentRunResult result = runtime.run(100L, 200L, "结合我现在的情况，给个建议",
                null, AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        assertThat(result.finalAnswer()).isEqualTo("结合记忆和站内文章，结论如下");
        assertThat(result.usedSteps()).isEqualTo(3);
    }

    @Test
    void askUserWhenInfoMissing() {
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.ASK_USER)
                        .withInput(Map.of("question", "你想了解或解决什么？")));

        AgentRunResult result = runtime.run(100L, 200L, "帮我看看",
                null, AgentStepEmitter.noop());

        // ASK_USER 终态：等用户补充信息（WAITING_USER），非 COMPLETED
        assertThat(result.status()).isEqualTo(AiAgentRunStatus.WAITING_USER);
        assertThat(result.finalAnswer()).isEqualTo("你想了解或解决什么？");
    }

    @Test
    void suggestWorkflowRejectedByWhitelist() {
        // 通用域白名单无 SUGGEST_WORKFLOW：非法动作直接 FAILED（无域内 Workflow 可建议）
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SUGGEST_WORKFLOW)
                        .withInput(Map.of("workflowType", "OPTIMIZE_ARTICLE")));

        AgentRunResult result = runtime.run(100L, 200L, "帮我优化",
                null, AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.FAILED);
        AiAgentRun saved = captureRun();
        assertThat(saved.getErrorMessage()).contains("非法动作");
    }

    @Test
    void suggestWriteRejectedByWhitelist() {
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SUGGEST_WRITE)
                        .withInput(Map.of("taskTitle", "缓存击穿")));

        AgentRunResult result = runtime.run(100L, 200L, "帮我把任务勾掉",
                null, AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.FAILED);
        AiAgentRun saved = captureRun();
        assertThat(saved.getErrorMessage()).contains("非法动作");
    }

    @Test
    void emitterReceivesStepEventsInOrder() {
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_MEMORY))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                        .withInput(Map.of("answer", "已结合你的情况回答")));
        when(executor.execute(any(), any(), any())).thenReturn("记忆摘要：\n- 语义记忆(USER_PREFERENCE)：正在学 Redis");

        List<String[]> events = new java.util.ArrayList<>();
        runtime.run(100L, 200L, "结合我的情况给个建议",
                null, (stepNo, actionType, status, message, thoughtSummary) ->
                        events.add(new String[]{String.valueOf(stepNo), actionType, status, message}));

        assertThat(events).hasSize(3);
        assertThat(events.get(0)[1]).isEqualTo("QUERY_MEMORY");
        assertThat(events.get(0)[2]).isEqualTo("RUNNING");
        assertThat(events.get(1)[2]).isEqualTo("SUCCESS");
        assertThat(events.get(2)[1]).isEqualTo("FINAL_ANSWER");
        assertThat(events.get(2)[2]).isEqualTo("SUCCESS");
        // 通用域 FINAL_ANSWER 文案（中性化，跨域一致）
        assertThat(events.get(2)[3]).isEqualTo("已生成最终回答");
    }

    @Test
    void maxStepsReachedProducesSummaryAtThree() {
        // 通用域 maxSteps=3（比学习/文章域 5 收紧）：到顶汇总，不再多轮决策
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_MEMORY));
        when(executor.execute(any(), any(), any())).thenReturn("第 X 条观察数据");

        AgentRunResult result = runtime.run(100L, 200L, "结合我的情况给个建议",
                null, AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        assertThat(result.usedSteps()).isEqualTo(3);
        assertThat(result.finalAnswer()).contains("根据已有信息");
    }

    @Test
    void createRunCancelsStalePendingSuggestions() {
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                        .withInput(Map.of("answer", "好的")));

        runtime.run(100L, 200L, "继续",
                null, AgentStepEmitter.noop());

        ArgumentCaptor<AiAgentRun> patchCaptor = ArgumentCaptor.forClass(AiAgentRun.class);
        verify(runMapper).update(patchCaptor.capture(), any());
        assertThat(patchCaptor.getValue().getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void executorFailureContinuesLoopAndFailsStep() {
        // 临时故障（非终局失败）：FAILED step 落库，循环继续由下一轮决策收尾
        when(decider.decide(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_MEMORY))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                        .withInput(Map.of("answer", "记忆查询失败，但先这样回答")));
        when(executor.execute(any(), any(), any())).thenThrow(new IllegalArgumentException("ES 暂时不可用"));

        AgentRunResult result = runtime.run(100L, 200L, "结合我的情况给个建议",
                null, AgentStepEmitter.noop());

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        assertThat(result.finalAnswer()).isEqualTo("记忆查询失败，但先这样回答");

        List<AiAgentStep> steps = captureSteps();
        assertThat(steps.get(0).getActionType()).isEqualTo("QUERY_MEMORY");
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
