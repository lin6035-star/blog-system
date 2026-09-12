package com.hailin.blogsystem;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.agent.AgentRunInspectionService;
import com.hailin.blogsystem.ai.agent.AgentStepLabelSupport;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.AiAgentStep;
import com.hailin.blogsystem.entity.vo.AgentRunDetailVO;
import com.hailin.blogsystem.entity.vo.AgentRunSummaryVO;
import com.hailin.blogsystem.entity.vo.AgentStepVO;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.exception.BusinessException;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.mapper.AiAgentStepMapper;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Agent Run inspection 层测试（V2.2）。
 * 全部 mock：验证只读语义、归属校验、安全摘要字段、steps 排序、分页。
 */
class AgentRunInspectionServiceTests {

    private static final String WAITING_CONTEXT = "{\"pendingWorkflowSuggestion\":{"
            + "\"workflowType\":\"LEARNING_PROGRESS\","
            + "\"reason\":\"计划进度与反馈不匹配\","
            + "\"initialMessage\":\"我最近学 Redis 有点乱\","
            + "\"risk\":\"MEDIUM\"}}";

    private AiAgentRunMapper runMapper;
    private AiAgentStepMapper stepMapper;
    private AgentRunInspectionService service;

    @BeforeEach
    void setUp() {
        runMapper = mock(AiAgentRunMapper.class);
        stepMapper = mock(AiAgentStepMapper.class);
        service = new AgentRunInspectionService(runMapper, stepMapper, new ObjectMapper());
        UserContext.set(100L);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void detailReturnsSafeSummaryWithSuggestionWhenWaiting() {
        when(runMapper.selectById(1L)).thenReturn(
                run(1L, 100L, "WAITING_WORKFLOW_CONFIRM", WAITING_CONTEXT));

        AgentRunDetailVO vo = service.getDetail(1L);

        assertThat(vo.getStatus()).isEqualTo("WAITING_WORKFLOW_CONFIRM");
        assertThat(vo.getGoal()).isEqualTo("我最近学 Redis 有点乱");
        assertThat(vo.getUsedSteps()).isEqualTo(2);
        assertThat(vo.getMaxSteps()).isEqualTo(5);
        assertThat(vo.getPendingWorkflowSuggestion()).isNotNull();
        assertThat(vo.getPendingWorkflowSuggestion().workflowType()).isEqualTo("LEARNING_PROGRESS");
    }

    @Test
    void detailOmitsSuggestionWhenNotWaiting() {
        when(runMapper.selectById(1L)).thenReturn(
                run(1L, 100L, "COMPLETED", "{\"observations\":\"...\"}"));

        AgentRunDetailVO vo = service.getDetail(1L);

        assertThat(vo.getStatus()).isEqualTo("COMPLETED");
        assertThat(vo.getPendingWorkflowSuggestion()).isNull();
    }

    @Test
    void detailRejectsForeignRun() {
        when(runMapper.selectById(1L)).thenReturn(run(1L, 999L, "COMPLETED", null));

        assertThatThrownBy(() -> service.getDetail(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权");
    }

    @Test
    void stepsExposeSummaryAndConsistentMessage() {
        when(runMapper.selectById(1L)).thenReturn(run(1L, 100L, "COMPLETED", null));
        // 排序由 DB orderByAsc(stepNo) 保证（wrapper 已带），mock 直接按序返回
        AiAgentStep suggestStep = step(2, "SUGGEST_WORKFLOW", "{\"summary\":\"建议启动学习进度\"}");
        suggestStep.setInputJson("{\"workflowType\":\"LEARNING_PROGRESS\",\"reason\":\"学乱了\"}");
        when(stepMapper.selectList(any())).thenReturn(List.of(
                step(1, "QUERY_LEARNING_DASHBOARD", "{\"summary\":\"学习计划总览：共 1 个计划\"}"),
                suggestStep
        ));

        List<AgentStepVO> steps = service.listSteps(1L);

        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).getStepNo()).isEqualTo(1);
        assertThat(steps.get(0).getActionType()).isEqualTo("QUERY_LEARNING_DASHBOARD");
        assertThat(steps.get(1).getStepNo()).isEqualTo(2);
        // summary 来自 outputJson.summary，不吐 inputJson
        assertThat(steps.get(0).getSummary()).isEqualTo("学习计划总览：共 1 个计划");
        // message 与实时 AGENT_STEP 事件一致（刷新前后思考面板不串味）
        assertThat(steps.get(0).getMessage()).isEqualTo("已完成查询学习计划");
        assertThat(steps.get(1).getMessage()).isEqualTo("建议启动「调整学习进度」流程");
    }

    @Test
    void listStepsNormalizesLegacyDuplicateFailureToSkipped() {
        when(runMapper.selectById(1L)).thenReturn(run(1L, 100L, "COMPLETED", null));
        AiAgentStep legacy = step(2, "QUERY_ARTICLE", null);
        legacy.setStatus("FAILED");
        legacy.setErrorMessage(AgentStepLabelSupport.DUPLICATE_QUERY_SKIP_PREFIX
                + "：同一动作 + 同一参数在第 1 步已成功执行过");
        when(stepMapper.selectList(any())).thenReturn(List.of(legacy));

        List<AgentStepVO> steps = service.listSteps(1L);

        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getStatus()).isEqualTo("SKIPPED");
        assertThat(steps.get(0).getMessage()).isEqualTo(AgentStepLabelSupport.DUPLICATE_QUERY_SKIP_MESSAGE);
    }

    @Test
    void listRunsFiltersBySessionAndPaginates() {
        Page<AiAgentRun> pageResult = new Page<>(1, 20);
        pageResult.setRecords(List.of(run(1L, 100L, "COMPLETED", null)));
        pageResult.setTotal(1);
        when(runMapper.selectPage(any(), any())).thenReturn(pageResult);

        PageVO<AgentRunSummaryVO> page = service.listRuns(200L, 1L, 20L);

        assertThat(page.getList()).hasSize(1);
        assertThat(page.getList().get(0).getId()).isEqualTo(1L);
        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getList().get(0).getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void requiresLogin() {
        UserContext.clear();

        assertThatThrownBy(() -> service.getDetail(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("登录");
    }

    private AiAgentRun run(Long id, Long userId, String status, String contextJson) {
        AiAgentRun run = new AiAgentRun();
        run.setId(id);
        run.setUserId(userId);
        run.setSessionId(200L);
        run.setGoal("我最近学 Redis 有点乱");
        run.setStatus(status);
        run.setCurrentStep(2);
        run.setUsedSteps(2);
        run.setMaxSteps(5);
        run.setContextJson(contextJson);
        run.setFinalAnswer("建议启动「LEARNING_PROGRESS」：计划进度与反馈不匹配");
        return run;
    }

    private AiAgentStep step(Integer stepNo, String actionType, String outputJson) {
        AiAgentStep step = new AiAgentStep();
        step.setStepNo(stepNo);
        step.setActionType(actionType);
        step.setStatus("SUCCESS");
        step.setOutputJson(outputJson);
        return step;
    }
}
