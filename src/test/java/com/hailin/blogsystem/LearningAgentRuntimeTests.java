package com.hailin.blogsystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.agent.AgentStepDecision;
import com.hailin.blogsystem.ai.agent.AgentStepDecider;
import com.hailin.blogsystem.ai.agent.AgentRunResult;
import com.hailin.blogsystem.ai.agent.LearningAgentActionExecutor;
import com.hailin.blogsystem.ai.agent.LearningAgentRuntime;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.AiAgentStep;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import com.hailin.blogsystem.entity.vo.LearningPlansDetailVO;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.mapper.AiAgentStepMapper;
import com.hailin.blogsystem.service.LearningPlansService;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Learning Agent 核心循环测试。
 * 决策器和执行器全部 mock，只验证循环编排 + 白名单校验 + 终态语义 + 落库。
 */
class LearningAgentRuntimeTests {

    private AgentStepDecider decider;
    private LearningAgentActionExecutor executor;
    private AiAgentRunMapper runMapper;
    private AiAgentStepMapper stepMapper;
    private LearningPlansService learningPlansService;
    private LearningAgentRuntime runtime;

    @BeforeEach
    void setUp() {
        decider = mock(AgentStepDecider.class);
        executor = mock(LearningAgentActionExecutor.class);
        runMapper = mock(AiAgentRunMapper.class);
        stepMapper = mock(AiAgentStepMapper.class);
        learningPlansService = mock(LearningPlansService.class);

        // MyBatis-Plus ASSIGN_ID 在 mock 下不生效，insert 时手动赋 id
        when(runMapper.insert(any(AiAgentRun.class))).thenAnswer(inv -> {
            AiAgentRun run = inv.getArgument(0);
            run.setId(1L);
            return 1;
        });

        runtime = new LearningAgentRuntime(
                decider, executor, runMapper, stepMapper, new ObjectMapper(), learningPlansService
        );
    }

    @Test
    void finalAnswerCompletesRun() {
        when(decider.decide(any(), any(), anyInt(), anyInt())).thenReturn(
                AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                        .withInput(Map.of("answer", "今天建议学 Redis 持久化"))
        );

        AgentRunResult result = runtime.run(100L, 200L, "今天继续学 Redis");

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        assertThat(result.finalAnswer()).isEqualTo("今天建议学 Redis 持久化");
        // 终态 FINAL_ANSWER 也计入一步，与 step 表数量一致
        assertThat(result.usedSteps()).isEqualTo(1);

        AiAgentRun saved = captureRun();
        assertThat(saved.getStatus()).isEqualTo("COMPLETED");
        assertThat(saved.getFinalAnswer()).isEqualTo("今天建议学 Redis 持久化");

        // 终态动作也落 step
        List<AiAgentStep> steps = captureSteps();
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getActionType()).isEqualTo("FINAL_ANSWER");
    }

    @Test
    void askUserMarksWaitingUser() {
        when(decider.decide(any(), any(), anyInt(), anyInt())).thenReturn(
                AgentStepDecision.of(AgentStepActionType.ASK_USER)
                        .withInput(Map.of("question", "你有多个 Redis 相关计划，想学哪个？"))
        );

        AgentRunResult result = runtime.run(100L, 200L, "我今天继续学 Redis");

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.WAITING_USER);
        assertThat(result.finalAnswer()).isEqualTo("你有多个 Redis 相关计划，想学哪个？");

        AiAgentRun saved = captureRun();
        assertThat(saved.getStatus()).isEqualTo("WAITING_USER");
    }

    @Test
    void queryDashboardThenFinalAnswer() {
        when(decider.decide(any(), any(), anyInt(), anyInt()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_LEARNING_DASHBOARD))
                .thenReturn(
                        AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                                .withInput(Map.of("answer", "建议继续学 Redis 缓存击穿"))
                );
        when(executor.execute(any(), any(), any())).thenReturn("找到 1 个活跃计划：Redis 学习计划（进度 40%）");

        AgentRunResult result = runtime.run(100L, 200L, "帮我安排今天学什么");

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        assertThat(result.finalAnswer()).isEqualTo("建议继续学 Redis 缓存击穿");
        // 查询 1 步 + 终态 1 步
        assertThat(result.usedSteps()).isEqualTo(2);
        verify(executor, times(1)).execute(any(), any(), any());

        // 2 条 step：查询 + 终态回答
        List<AiAgentStep> steps = captureSteps();
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).getActionType()).isEqualTo("QUERY_LEARNING_DASHBOARD");
        assertThat(steps.get(0).getStatus()).isEqualTo("SUCCESS");

        // observation 进上下文
        AiAgentRun saved = captureRun();
        assertThat(saved.getContextJson()).contains("Redis 学习计划");
    }

    @Test
    void nullDecisionFailsRun() {
        when(decider.decide(any(), any(), anyInt(), anyInt())).thenReturn(null);

        AgentRunResult result = runtime.run(100L, 200L, "今天学什么");

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.FAILED);
        AiAgentRun saved = captureRun();
        assertThat(saved.getErrorMessage()).contains("空决策");
    }

    @Test
    void deciderExceptionFailsRun() {
        when(decider.decide(any(), any(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("LLM 调用失败"));

        AgentRunResult result = runtime.run(100L, 200L, "今天学什么");

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.FAILED);
        assertThat(result.finalAnswer()).isNull();
    }

    @Test
    void maxStepsReachedProducesSummary() {
        // 永远不终态，只查询，跑满 maxSteps(5)
        when(decider.decide(any(), any(), anyInt(), anyInt()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_LEARNING_DASHBOARD));
        when(executor.execute(any(), any(), any())).thenReturn("第 X 条观察数据");

        AgentRunResult result = runtime.run(100L, 200L, "帮我安排今天学什么");

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        assertThat(result.usedSteps()).isEqualTo(5);
        assertThat(result.finalAnswer()).contains("整理如下");
        assertThat(result.finalAnswer()).contains("第 X 条观察数据");
    }

    @Test
    void zeroObservationSuggestionRejectedThenContinues() {
        // V2.1 裁判：首轮零观察 SUGGEST_WORKFLOW 被拒（没查就拍脑袋），
        // 落 FAILED step + 拒绝提示进上下文，循环继续
        when(decider.decide(any(), any(), anyInt(), anyInt()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SUGGEST_WORKFLOW)
                        .withInput(Map.of("workflowType", "LEARNING_PROGRESS", "reason", "学乱了")))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                        .withInput(Map.of("answer", "先了解一下你的学习进度，我再帮你安排")));

        AgentRunResult result = runtime.run(100L, 200L, "我最近学 Redis 有点乱");

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        assertThat(result.usedSteps()).isEqualTo(2);
        // 被拒的建议落 FAILED step
        List<AiAgentStep> steps = captureSteps();
        assertThat(steps.get(0).getActionType()).isEqualTo("SUGGEST_WORKFLOW");
        assertThat(steps.get(0).getStatus()).isEqualTo("FAILED");
        assertThat(steps.get(0).getErrorMessage()).contains("零观察");
        // 拒绝提示进上下文，下一轮 LLM 可感知
        AiAgentRun saved = captureRun();
        assertThat(saved.getContextJson()).contains("后端已拒绝");
    }

    @Test
    void suggestionWithObservationMarksWaitingWorkflowConfirm() {
        // V2.1：先查询（有观察）再建议 → WAITING_WORKFLOW_CONFIRM + suggestion 透出 + context 落库
        when(decider.decide(any(), any(), anyInt(), anyInt()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_LEARNING_DASHBOARD))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SUGGEST_WORKFLOW)
                        .withInput(Map.of(
                                "workflowType", "LEARNING_PROGRESS",
                                "reason", "当前计划阶段与学习反馈不匹配",
                                "initialMessage", "我最近学 Redis 有点乱")));
        when(executor.execute(any(), any(), any()))
                .thenReturn("学习计划总览：共 1 个计划，1 个进行中。\n目标计划《Redis 学习计划》");

        AgentRunResult result = runtime.run(100L, 200L, "我最近学 Redis 有点乱");

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.WAITING_WORKFLOW_CONFIRM);
        assertThat(result.pendingWorkflowSuggestion()).isNotNull();
        assertThat(result.pendingWorkflowSuggestion().workflowType()).isEqualTo("LEARNING_PROGRESS");
        assertThat(result.pendingWorkflowSuggestion().initialMessage()).isEqualTo("我最近学 Redis 有点乱");
        // 正文直接用 reason（不带"建议启动「X」："前缀）
        assertThat(result.finalAnswer()).isEqualTo("当前计划阶段与学习反馈不匹配");
        assertThat(result.finalAnswer()).doesNotContain("建议启动");

        AiAgentRun saved = captureRun();
        assertThat(saved.getStatus()).isEqualTo("WAITING_WORKFLOW_CONFIRM");
        assertThat(saved.getContextJson()).contains("pendingWorkflowSuggestion");
        assertThat(saved.getContextJson()).contains("LEARNING_PROGRESS");

        List<AiAgentStep> steps = captureSteps();
        assertThat(steps.get(1).getActionType()).isEqualTo("SUGGEST_WORKFLOW");
        assertThat(steps.get(1).getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void forbiddenWorkflowTypeFailsRun() {
        // V2.1：建议文章类 Workflow 不在 allowlist → FAILED
        when(decider.decide(any(), any(), anyInt(), anyInt()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_LEARNING_DASHBOARD))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SUGGEST_WORKFLOW)
                        .withInput(Map.of("workflowType", "CREATE_ARTICLE", "reason", "想写文章")));
        when(executor.execute(any(), any(), any())).thenReturn("学习计划总览：共 1 个计划");

        AgentRunResult result = runtime.run(100L, 200L, "帮我写文章");

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.FAILED);
        AiAgentRun saved = captureRun();
        assertThat(saved.getErrorMessage()).contains("不在允许范围内");
    }

    @Test
    void emitterReceivesStepEventsInOrder() {
        // V2.3：思考面板事件序列——动作 RUNNING → SUCCESS，终态 SUCCESS
        when(decider.decide(any(), any(), anyInt(), anyInt()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_LEARNING_DASHBOARD))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                        .withInput(Map.of("answer", "建议继续学 Redis")));
        when(executor.execute(any(), any(), any())).thenReturn("找到 1 个活跃计划");

        List<String[]> events = new java.util.ArrayList<>();
        runtime.run(100L, 200L, "帮我安排今天学什么", (stepNo, actionType, status, message) ->
                events.add(new String[]{String.valueOf(stepNo), actionType, status, message}));

        assertThat(events).hasSize(3);
        // 第 1 步：查询 RUNNING → SUCCESS
        assertThat(events.get(0)[0]).isEqualTo("1");
        assertThat(events.get(0)[1]).isEqualTo("QUERY_LEARNING_DASHBOARD");
        assertThat(events.get(0)[2]).isEqualTo("RUNNING");
        assertThat(events.get(1)[0]).isEqualTo("1");
        assertThat(events.get(1)[1]).isEqualTo("QUERY_LEARNING_DASHBOARD");
        assertThat(events.get(1)[2]).isEqualTo("SUCCESS");
        // 第 2 步：终态 FINAL_ANSWER SUCCESS
        assertThat(events.get(2)[0]).isEqualTo("2");
        assertThat(events.get(2)[1]).isEqualTo("FINAL_ANSWER");
        assertThat(events.get(2)[2]).isEqualTo("SUCCESS");
    }

    @Test
    void writeProposalWithObservationMarksWaitingWriteConfirm() {
        // V2.4：先查询（有观察）再提案写动作 → WAITING_WRITE_CONFIRM + proposal 透出
        when(decider.decide(any(), any(), anyInt(), anyInt()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_LEARNING_DASHBOARD))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SUGGEST_WRITE)
                        .withInput(Map.of(
                                "taskTitle", "缓存击穿",
                                "stageTitle", "阶段一",
                                "done", "true")));
        when(executor.execute(any(), any(), any()))
                .thenReturn("学习计划总览：共 1 个计划。\n目标计划《Redis 学习计划》\n- 阶段《阶段一》：缓存击穿；");

        AgentRunResult result = runtime.run(100L, 200L, "帮我把缓存击穿勾掉");

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.WAITING_WRITE_CONFIRM);
        assertThat(result.pendingWriteAction()).isNotNull();
        assertThat(result.pendingWriteAction().taskTitle()).isEqualTo("缓存击穿");
        assertThat(result.pendingWriteAction().done()).isTrue();

        AiAgentRun saved = captureRun();
        assertThat(saved.getStatus()).isEqualTo("WAITING_WRITE_CONFIRM");
        assertThat(saved.getContextJson()).contains("pendingWriteAction");
        assertThat(saved.getContextJson()).contains("缓存击穿");
    }

    @Test
    void addTaskProposalWithObservationMarksWaitingWriteConfirm() {
        // V3.1：内层 actionType=ADD_LEARNING_TASK（外层仍是 SUGGEST_WRITE，双层同名字段分层语义）
        when(decider.decide(any(), any(), anyInt(), anyInt()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_LEARNING_DASHBOARD))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SUGGEST_WRITE)
                        .withInput(Map.of(
                                "actionType", "ADD_LEARNING_TASK",
                                "planRef", "Redis 学习计划",
                                "stageTitle", "第二阶段",
                                "taskTitle", "缓存雪崩防护")));
        when(executor.execute(any(), any(), any()))
                .thenReturn("学习计划总览：共 1 个计划。\n目标计划《Redis 学习计划》\n- 阶段《第二阶段》：缓存穿透；");

        AgentRunResult result = runtime.run(100L, 200L, "给 Redis 计划第二阶段加一个缓存雪崩防护任务");

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.WAITING_WRITE_CONFIRM);
        assertThat(result.pendingWriteAction()).isNotNull();
        assertThat(result.pendingWriteAction().actionType()).isEqualTo("ADD_LEARNING_TASK");
        assertThat(result.pendingWriteAction().taskTitle()).isEqualTo("缓存雪崩防护");

        AiAgentRun saved = captureRun();
        assertThat(saved.getContextJson()).contains("ADD_LEARNING_TASK");
    }

    @Test
    void addTaskProposalRejectedWhenDuplicateTaskExists() {
        // V3.1 补充：目标阶段已存在同名任务 → 提案前预检拒绝（FAILED step）→ LLM 直接告知，
        // 不弹确认卡（原来 confirm 才报「已存在」，用户确认了个寂寞）
        when(decider.decide(any(), any(), anyInt(), anyInt()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_LEARNING_DASHBOARD))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SUGGEST_WRITE)
                        .withInput(Map.of(
                                "actionType", "ADD_LEARNING_TASK",
                                "planRef", "Redis 学习计划",
                                "stageTitle", "阶段二",
                                "taskTitle", "缓存雪崩防护")))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                        .withInput(Map.of("answer", "这个任务已经在你的阶段二里了，不用重复添加。")));
        when(executor.execute(any(), any(), any()))
                .thenReturn("学习计划总览：共 1 个计划。\n- 阶段《阶段二》：缓存穿透；缓存雪崩防护；");
        when(learningPlansService.listByUser(100L)).thenReturn(List.of(activePlan(1L, "Redis 学习计划")));
        when(learningPlansService.matchActivePlansByMessage(100L, "Redis 学习计划"))
                .thenReturn(List.of(activePlan(1L, "Redis 学习计划")));
        when(learningPlansService.getDetail(1L, 100L)).thenReturn(
                detailWithStages(stageWithTasks(11L, "阶段二", "缓存雪崩防护")));

        AgentRunResult result = runtime.run(100L, 200L, "给 Redis 计划第二阶段加一个缓存雪崩防护任务");

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        assertThat(result.finalAnswer()).contains("已经在你的阶段二里");
        assertThat(result.pendingWriteAction()).isNull();
        // 提案被拒：SUGGEST_WRITE step 记 FAILED（第 2 步），observation 进上下文让 LLM 收尾
        List<AiAgentStep> steps = captureSteps();
        assertThat(steps).hasSize(3);
        assertThat(steps.get(1).getActionType()).isEqualTo("SUGGEST_WRITE");
        assertThat(steps.get(1).getStatus()).isEqualTo("FAILED");
        assertThat(steps.get(1).getErrorMessage()).contains("已存在同名任务");
    }

    @Test
    void renameProposalWithObservationMarksWaitingWriteConfirm() {
        // V3.3：内层 actionType=UPDATE_LEARNING_TASK → proposal 带 taskTitle(旧名) + newTitle(新名)，done 恒 false
        when(decider.decide(any(), any(), anyInt(), anyInt()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_LEARNING_DASHBOARD))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SUGGEST_WRITE)
                        .withInput(Map.of(
                                "actionType", "UPDATE_LEARNING_TASK",
                                "planRef", "Redis 学习计划",
                                "stageTitle", "第二阶段",
                                "taskTitle", "缓存击穿",
                                "newTitle", "缓存击穿防护")));
        when(executor.execute(any(), any(), any()))
                .thenReturn("学习计划总览：共 1 个计划。\n- 阶段《第二阶段》：缓存击穿；");

        AgentRunResult result = runtime.run(100L, 200L, "把缓存击穿任务改名为缓存击穿防护");

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.WAITING_WRITE_CONFIRM);
        assertThat(result.pendingWriteAction()).isNotNull();
        assertThat(result.pendingWriteAction().actionType()).isEqualTo("UPDATE_LEARNING_TASK");
        assertThat(result.pendingWriteAction().taskTitle()).isEqualTo("缓存击穿");
        assertThat(result.pendingWriteAction().newTitle()).isEqualTo("缓存击穿防护");
        assertThat(result.pendingWriteAction().done()).isFalse();

        AiAgentRun saved = captureRun();
        assertThat(saved.getContextJson()).contains("UPDATE_LEARNING_TASK");
        assertThat(saved.getContextJson()).contains("缓存击穿防护");
    }

    @Test
    void renameProposalRejectedWhenNewTitleAlreadyExists() {
        // V3.3：新名与阶段内其他任务撞名 → 提案前预检拒绝（FAILED step）→ LLM 直接告知，不弹确认卡
        when(decider.decide(any(), any(), anyInt(), anyInt()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_LEARNING_DASHBOARD))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SUGGEST_WRITE)
                        .withInput(Map.of(
                                "actionType", "UPDATE_LEARNING_TASK",
                                "planRef", "Redis 学习计划",
                                "stageTitle", "阶段二",
                                "taskTitle", "缓存击穿",
                                "newTitle", "缓存雪崩防护")))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                        .withInput(Map.of("answer", "阶段二里已经有缓存雪崩防护这个任务了，换个名字吧。")));
        when(executor.execute(any(), any(), any()))
                .thenReturn("学习计划总览：共 1 个计划。\n- 阶段《阶段二》：缓存击穿；缓存雪崩防护；");
        when(learningPlansService.listByUser(100L)).thenReturn(List.of(activePlan(1L, "Redis 学习计划")));
        when(learningPlansService.matchActivePlansByMessage(100L, "Redis 学习计划"))
                .thenReturn(List.of(activePlan(1L, "Redis 学习计划")));
        when(learningPlansService.getDetail(1L, 100L)).thenReturn(
                detailWithStages(stageWithTasks(11L, "阶段二", "缓存击穿", "缓存雪崩防护")));

        AgentRunResult result = runtime.run(100L, 200L, "把缓存击穿改名为缓存雪崩防护");

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        assertThat(result.finalAnswer()).contains("换个名字吧");
        assertThat(result.pendingWriteAction()).isNull();
        List<AiAgentStep> steps = captureSteps();
        assertThat(steps).hasSize(3);
        assertThat(steps.get(1).getActionType()).isEqualTo("SUGGEST_WRITE");
        assertThat(steps.get(1).getStatus()).isEqualTo("FAILED");
        assertThat(steps.get(1).getErrorMessage()).contains("已存在同名任务");
    }

    @Test
    void renameProposalRejectedWhenNewTitleSameAsOld() {
        // V3.3 边界：同名改名（newTitle == taskTitle）——precheck 排除自身必漏，调用点显式拒绝
        when(decider.decide(any(), any(), anyInt(), anyInt()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_LEARNING_DASHBOARD))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SUGGEST_WRITE)
                        .withInput(Map.of(
                                "actionType", "UPDATE_LEARNING_TASK",
                                "stageTitle", "阶段一",
                                "taskTitle", "缓存击穿",
                                "newTitle", "缓存击穿")))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                        .withInput(Map.of("answer", "新名字和原来一样，不用改。")));
        when(executor.execute(any(), any(), any()))
                .thenReturn("学习计划总览：共 1 个计划。\n- 阶段《阶段一》：缓存击穿；");

        AgentRunResult result = runtime.run(100L, 200L, "把缓存击穿改名为缓存击穿");

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        assertThat(result.finalAnswer()).contains("不用改");
        assertThat(result.pendingWriteAction()).isNull();
        List<AiAgentStep> steps = captureSteps();
        assertThat(steps.get(1).getActionType()).isEqualTo("SUGGEST_WRITE");
        assertThat(steps.get(1).getStatus()).isEqualTo("FAILED");
        assertThat(steps.get(1).getErrorMessage()).contains("与原任务名相同");
    }

    @Test
    void renameProposalWithUnknownActionTypeFailsRun() {
        // V3.3（Codex 评审收紧）：非空但不认识的 actionType（模型幻觉 DELETE/MOVE 等）→ FAILED 不生成提案，
        // 绝不回落 UPDATE_TASK_DONE（回落 + done 缺省 true = 误勾选任务）
        when(decider.decide(any(), any(), anyInt(), anyInt()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_LEARNING_DASHBOARD))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SUGGEST_WRITE)
                        .withInput(Map.of(
                                "actionType", "DELETE_LEARNING_TASK",
                                "taskTitle", "缓存击穿")));
        when(executor.execute(any(), any(), any())).thenReturn("学习计划总览：共 1 个计划");

        AgentRunResult result = runtime.run(100L, 200L, "把缓存击穿任务删掉");

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.FAILED);
        AiAgentRun saved = captureRun();
        assertThat(saved.getErrorMessage()).contains("不支持的 actionType");
        assertThat(result.pendingWriteAction()).isNull();
        // markFailed 终局：run 落 FAILED，不再产出 WAITING_WRITE_CONFIRM 提案（无 pendingWriteAction 落库）
        assertThat(saved.getContextJson()).doesNotContain("pendingWriteAction");
    }

    private LearningPlans activePlan(Long id, String title) {
        LearningPlans plan = new LearningPlans();
        plan.setId(id);
        plan.setTitle(title);
        plan.setStatus(LearningPlans.STATUS_ACTIVE);
        return plan;
    }

    private LearningPlansDetailVO detailWithStages(LearningPlansDetailVO.StageProgress... stages) {
        LearningPlansDetailVO vo = new LearningPlansDetailVO();
        vo.setStages(List.of(stages));
        return vo;
    }

    private LearningPlansDetailVO.StageProgress stageWithTasks(Long id, String title, String... taskTitles) {
        LearningPlansDetailVO.StageProgress stage = new LearningPlansDetailVO.StageProgress();
        stage.setId(id);
        stage.setTitle(title);
        stage.setTasks(java.util.Arrays.stream(taskTitles).map(t -> {
            LearningPlansDetailVO.TaskItem item = new LearningPlansDetailVO.TaskItem();
            item.setTitle(t);
            item.setDone(false);
            return item;
        }).toList());
        return stage;
    }

    @Test
    void writeProposalWithoutTaskTitleFailsRun() {
        when(decider.decide(any(), any(), anyInt(), anyInt()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.QUERY_LEARNING_DASHBOARD))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SUGGEST_WRITE)
                        .withInput(Map.of("stageTitle", "阶段一")));
        when(executor.execute(any(), any(), any())).thenReturn("学习计划总览：共 1 个计划");

        AgentRunResult result = runtime.run(100L, 200L, "帮我把任务勾掉");

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.FAILED);
        AiAgentRun saved = captureRun();
        assertThat(saved.getErrorMessage()).contains("taskTitle");
    }

    @Test
    void zeroObservationWriteProposalRejectedThenContinues() {
        // V2.4：首轮零观察 SUGGEST_WRITE 被拒，循环继续
        when(decider.decide(any(), any(), anyInt(), anyInt()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SUGGEST_WRITE)
                        .withInput(Map.of("taskTitle", "缓存击穿")))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                        .withInput(Map.of("answer", "我先看一下你的学习计划")));

        AgentRunResult result = runtime.run(100L, 200L, "帮我把缓存击穿勾掉");

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        List<AiAgentStep> steps = captureSteps();
        assertThat(steps.get(0).getActionType()).isEqualTo("SUGGEST_WRITE");
        assertThat(steps.get(0).getStatus()).isEqualTo("FAILED");
        assertThat(steps.get(0).getErrorMessage()).contains("零观察");
    }

    @Test
    void createRunCancelsStalePendingSuggestions() {
        // V2.1：新 run 创建时把同 session 旧的 WAITING_WORKFLOW_CONFIRM 置 CANCELLED
        when(decider.decide(any(), any(), anyInt(), anyInt()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                        .withInput(Map.of("answer", "好的")));

        runtime.run(100L, 200L, "继续学");

        ArgumentCaptor<AiAgentRun> patchCaptor = ArgumentCaptor.forClass(AiAgentRun.class);
        verify(runMapper).update(patchCaptor.capture(), any());
        assertThat(patchCaptor.getValue().getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void executorFailureContinuesLoopAndFailsStep() {
        when(decider.decide(any(), any(), anyInt(), anyInt()))
                .thenReturn(AgentStepDecision.of(AgentStepActionType.SEARCH_RAG))
                .thenReturn(
                        AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                                .withInput(Map.of("answer", "RAG 失败但计划信息足够，建议继续"))
                );
        when(executor.execute(any(), any(), any())).thenThrow(new RuntimeException("ES 连接失败"));

        AgentRunResult result = runtime.run(100L, 200L, "Redis 怎么学");

        assertThat(result.status()).isEqualTo(AiAgentRunStatus.COMPLETED);
        assertThat(result.finalAnswer()).isEqualTo("RAG 失败但计划信息足够，建议继续");

        // 失败 step 记 FAILED，observation 带失败原因进上下文
        List<AiAgentStep> steps = captureSteps();
        assertThat(steps.get(0).getActionType()).isEqualTo("SEARCH_RAG");
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
