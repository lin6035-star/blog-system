package com.hailin.blogsystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.agent.AgentWriteActionService;
import com.hailin.blogsystem.ai.agent.AgentWriteActionView;
import com.hailin.blogsystem.ai.workflow.WorkflowActionLock;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.LearningStages;
import com.hailin.blogsystem.entity.vo.LearningPlansDetailVO;
import com.hailin.blogsystem.exception.BusinessException;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.mapper.LearningStageMapper;
import com.hailin.blogsystem.service.LearningPlansService;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 受控写动作测试（V2.4）。
 * 全部 mock：验证标题匹配裁判（不信任 LLM 索引）、确认/取消 CAS、before/after 快照留痕。
 */
class AgentWriteActionServiceTests {

    private static final String PROPOSAL_CONTEXT = "{\"pendingWriteAction\":{"
            + "\"actionType\":\"UPDATE_TASK_DONE\","
            + "\"taskTitle\":\"缓存击穿\","
            + "\"stageTitle\":\"阶段一\","
            + "\"done\":true}}";

    private AiAgentRunMapper runMapper;
    private LearningStageMapper learningStageMapper;
    private WorkflowActionLock actionLock;
    private LearningPlansService learningPlansService;
    private AgentWriteActionService service;

    @BeforeEach
    void setUp() {
        runMapper = mock(AiAgentRunMapper.class);
        learningStageMapper = mock(LearningStageMapper.class);
        actionLock = mock(WorkflowActionLock.class);
        when(actionLock.acquireOrThrow(anyString(), any()))
                .thenReturn(new WorkflowActionLock.LockHandle(1L, "key", "token"));
        learningPlansService = mock(LearningPlansService.class);

        service = new AgentWriteActionService(
                runMapper, learningStageMapper, new ObjectMapper(), actionLock, learningPlansService
        );
        UserContext.set(100L);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void confirmWriteExecutesAndRecordsSnapshot() {
        // 单 ACTIVE 计划（未点名）+ 阶段/任务标题唯一 → 执行
        when(runMapper.selectById(1L)).thenReturn(
                run(1L, "WAITING_WRITE_CONFIRM", PROPOSAL_CONTEXT));
        when(runMapper.update(any(AiAgentRun.class), any())).thenReturn(1);
        when(learningPlansService.listByUser(100L)).thenReturn(List.of(
                activePlan(1L, "Redis 学习计划")));
        when(learningPlansService.getDetail(1L, 100L)).thenReturn(detail(
                stage(11L, "阶段一", "缓存击穿")));
        LearningStages beforeStage = stageEntity("{\"tasks\":[{\"title\":\"缓存击穿\",\"done\":false}]}");
        LearningStages afterStage = stageEntity("{\"tasks\":[{\"title\":\"缓存击穿\",\"done\":true}]}");
        when(learningStageMapper.selectById(11L)).thenReturn(beforeStage, afterStage);

        String result = service.confirmWrite(1L);

        assertThat(result).contains("已勾选任务「缓存击穿」为完成");
        // 执行走现有 updateTaskDone（索引由后端匹配得出）
        verify(learningPlansService).updateTaskDone(eq(1L), eq(11L), eq(0), eq(true), eq(100L));
        // 快照留痕：COMPLETED + context 含 before/after
        ArgumentCaptor<AiAgentRun> patchCaptor = ArgumentCaptor.forClass(AiAgentRun.class);
        verify(runMapper, atLeastOnce()).updateById(patchCaptor.capture());
        AiAgentRun patch = patchCaptor.getValue();
        assertThat(patch.getStatus()).isEqualTo("COMPLETED");
        assertThat(patch.getContextJson()).contains("writeActionResult");
        assertThat(patch.getContextJson()).contains("beforeTasks");
        assertThat(patch.getContextJson()).contains("afterTasks");
    }

    @Test
    void confirmWriteRejectsWhenTaskTitleNotFound() {
        when(runMapper.selectById(1L)).thenReturn(
                run(1L, "WAITING_WRITE_CONFIRM", PROPOSAL_CONTEXT));
        when(runMapper.update(any(AiAgentRun.class), any())).thenReturn(1);
        when(learningPlansService.listByUser(100L)).thenReturn(List.of(
                activePlan(1L, "Redis 学习计划")));
        // 阶段存在但任务标题不匹配
        when(learningPlansService.getDetail(1L, 100L)).thenReturn(detail(
                stage(11L, "阶段一", "别的任务")));

        assertThatThrownBy(() -> service.confirmWrite(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有找到任务");
        verify(learningPlansService, never()).updateTaskDone(any(), any(), anyInt(), anyBoolean(), any());
    }

    @Test
    void confirmWriteRejectsDuplicateTaskTitle() {
        when(runMapper.selectById(1L)).thenReturn(
                run(1L, "WAITING_WRITE_CONFIRM", PROPOSAL_CONTEXT));
        when(runMapper.update(any(AiAgentRun.class), any())).thenReturn(1);
        when(learningPlansService.listByUser(100L)).thenReturn(List.of(
                activePlan(1L, "Redis 学习计划")));
        // 同名任务两个
        LearningPlansDetailVO.StageProgress stage = stage(11L, "阶段一", "缓存击穿");
        stage.setTasks(List.of(task("缓存击穿"), task("缓存击穿")));
        when(learningPlansService.getDetail(1L, 100L)).thenReturn(detail(stage));

        assertThatThrownBy(() -> service.confirmWrite(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("同名");
        verify(learningPlansService, never()).updateTaskDone(any(), any(), anyInt(), anyBoolean(), any());
    }

    @Test
    void confirmWriteRejectsMultipleActivePlans() {
        when(runMapper.selectById(1L)).thenReturn(
                run(1L, "WAITING_WRITE_CONFIRM", PROPOSAL_CONTEXT));
        when(runMapper.update(any(AiAgentRun.class), any())).thenReturn(1);
        when(learningPlansService.listByUser(100L)).thenReturn(List.of(
                activePlan(1L, "Redis 计划"), activePlan(2L, "Java 计划")));

        assertThatThrownBy(() -> service.confirmWrite(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("多个进行中的计划");
    }

    @Test
    void confirmWriteRejectsWhenNotWaiting() {
        when(runMapper.selectById(1L)).thenReturn(run(1L, "COMPLETED", null));

        assertThatThrownBy(() -> service.confirmWrite(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已被处理或已过期");
    }

    @Test
    void confirmWriteFailsWhenCasConsumed() {
        when(runMapper.selectById(1L)).thenReturn(
                run(1L, "WAITING_WRITE_CONFIRM", PROPOSAL_CONTEXT));
        when(runMapper.update(any(AiAgentRun.class), any())).thenReturn(0);

        assertThatThrownBy(() -> service.confirmWrite(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已被处理");
        verify(learningPlansService, never()).updateTaskDone(any(), any(), anyInt(), anyBoolean(), any());
    }

    @Test
    void cancelWriteMarksCancelled() {
        when(runMapper.selectById(1L)).thenReturn(
                run(1L, "WAITING_WRITE_CONFIRM", PROPOSAL_CONTEXT));
        when(runMapper.update(any(AiAgentRun.class), any())).thenReturn(1);

        String message = service.cancelWrite(1L);

        assertThat(message).contains("已取消");
        ArgumentCaptor<AiAgentRun> captor = ArgumentCaptor.forClass(AiAgentRun.class);
        verify(runMapper).update(captor.capture(), any());
        assertThat(captor.getValue().getStatus()).isEqualTo("CANCELLED");
        verify(learningPlansService, never()).updateTaskDone(any(), any(), anyInt(), anyBoolean(), any());
    }

    @Test
    void getWriteActionReturnsPendingProposal() {
        when(runMapper.selectById(1L)).thenReturn(
                run(1L, "WAITING_WRITE_CONFIRM", PROPOSAL_CONTEXT));

        AgentWriteActionView view = service.getWriteAction(1L);

        assertThat(view.status()).isEqualTo("WAITING_WRITE_CONFIRM");
        assertThat(view.proposal()).isNotNull();
        assertThat(view.proposal().taskTitle()).isEqualTo("缓存击穿");
    }

    private AiAgentRun run(Long id, String status, String contextJson) {
        AiAgentRun run = new AiAgentRun();
        run.setId(id);
        run.setUserId(100L);
        run.setSessionId(200L);
        run.setStatus(status);
        run.setContextJson(contextJson);
        return run;
    }

    private LearningPlans activePlan(Long id, String title) {
        LearningPlans plan = new LearningPlans();
        plan.setId(id);
        plan.setTitle(title);
        plan.setStatus(LearningPlans.STATUS_ACTIVE);
        return plan;
    }

    private LearningPlansDetailVO detail(LearningPlansDetailVO.StageProgress... stages) {
        LearningPlansDetailVO vo = new LearningPlansDetailVO();
        vo.setStages(List.of(stages));
        return vo;
    }

    private LearningPlansDetailVO.StageProgress stage(Long id, String title, String... taskTitles) {
        LearningPlansDetailVO.StageProgress stage = new LearningPlansDetailVO.StageProgress();
        stage.setId(id);
        stage.setTitle(title);
        stage.setTasks(java.util.Arrays.stream(taskTitles).map(t -> task(t)).toList());
        return stage;
    }

    private LearningPlansDetailVO.TaskItem task(String title) {
        LearningPlansDetailVO.TaskItem item = new LearningPlansDetailVO.TaskItem();
        item.setTitle(title);
        item.setDone(false);
        return item;
    }

    private LearningStages stageEntity(String tasksJson) {
        LearningStages stage = new LearningStages();
        stage.setId(11L);
        stage.setTasks(tasksJson);
        return stage;
    }
}
