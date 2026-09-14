package com.hailin.blogsystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.agent.AgentRunSuggestionService;
import com.hailin.blogsystem.ai.agent.AgentRunSuggestionView;
import com.hailin.blogsystem.ai.agent.ArticleSessionAnchorService;
import com.hailin.blogsystem.ai.workflow.WorkflowActionIdempotency;
import com.hailin.blogsystem.ai.workflow.WorkflowActionLock;
import com.hailin.blogsystem.ai.workflow.WorkflowRunManager;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.dto.AiWorkflowLearningAssistDTO;
import com.hailin.blogsystem.entity.dto.AiWorkflowLearningPlanDTO;
import com.hailin.blogsystem.entity.vo.AiWorkflowRunVO;
import com.hailin.blogsystem.exception.BusinessException;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.service.AiWorkflowRunService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 建议确认 / 取消服务测试（V2.1）。
 * 全部 mock：验证归属校验、状态 CAS 一次性消费、幂等命中、active Workflow 冲突、
 * confirm 复用现有 Workflow 创建路径、cancel 语义。
 */
class AgentRunSuggestionServiceTests {

    private static final String SUGGESTION_CONTEXT = "{\"pendingWorkflowSuggestion\":{"
            + "\"workflowType\":\"LEARNING_PLAN\","
            + "\"reason\":\"当前没有学习计划\","
            + "\"initialMessage\":\"我想系统学 Redis\","
            + "\"risk\":\"MEDIUM\"}}";

    private AiAgentRunMapper runMapper;
    private WorkflowActionLock actionLock;
    private WorkflowActionIdempotency idempotency;
    private AiWorkflowRunService aiWorkflowRunService;
    private WorkflowRunManager workflowRunManager;
    private LearningPlansService learningPlansService;
    private ArticleSessionAnchorService anchorService;
    private AgentRunSuggestionService service;

    @BeforeEach
    void setUp() {
        runMapper = mock(AiAgentRunMapper.class);
        actionLock = mock(WorkflowActionLock.class);
        when(actionLock.acquireOrThrow(anyString(), any()))
                .thenReturn(new WorkflowActionLock.LockHandle(1L, "key", "token"));
        idempotency = mock(WorkflowActionIdempotency.class);
        aiWorkflowRunService = mock(AiWorkflowRunService.class);
        workflowRunManager = mock(WorkflowRunManager.class);
        learningPlansService = mock(LearningPlansService.class);

        anchorService = mock(ArticleSessionAnchorService.class);

        service = new AgentRunSuggestionService(
                runMapper,
                mock(com.hailin.blogsystem.mapper.AiMessageMapper.class),
                new ObjectMapper(),
                actionLock,
                idempotency,
                aiWorkflowRunService,
                workflowRunManager,
                learningPlansService,
                anchorService
        );
        UserContext.set(100L);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void confirmStartsWorkflowAndMarksCompleted() {
        when(runMapper.selectById(1L)).thenReturn(
                run(1L, 100L, "WAITING_WORKFLOW_CONFIRM", SUGGESTION_CONTEXT));
        when(runMapper.update(any(AiAgentRun.class), any())).thenReturn(1);

        AiWorkflowRunVO vo = new AiWorkflowRunVO();
        vo.setId("wf-1");
        when(aiWorkflowRunService.createLearningPlanWorkflow(any(AiWorkflowLearningPlanDTO.class)))
                .thenReturn(vo);

        AiWorkflowRunVO result = service.confirm(1L, null);

        assertThat(result.getId()).isEqualTo("wf-1");
        // 转 DTO 时 goal 用建议的 initialMessage
        ArgumentCaptor<AiWorkflowLearningPlanDTO> dtoCaptor =
                ArgumentCaptor.forClass(AiWorkflowLearningPlanDTO.class);
        verify(aiWorkflowRunService).createLearningPlanWorkflow(dtoCaptor.capture());
        assertThat(dtoCaptor.getValue().getGoal()).isEqualTo("我想系统学 Redis");

        // run 标记 COMPLETED + workflowRunId 落 context
        ArgumentCaptor<AiAgentRun> patchCaptor = ArgumentCaptor.forClass(AiAgentRun.class);
        verify(runMapper, atLeastOnce()).updateById(patchCaptor.capture());
        AiAgentRun patch = patchCaptor.getValue();
        assertThat(patch.getStatus()).isEqualTo("COMPLETED");
        assertThat(patch.getContextJson()).contains("workflowRunId");
        assertThat(patch.getContextJson()).contains("wf-1");
    }

    @Test
    void confirmAssistSuggestionWithMessageNamedPlanSetsPlanId() {
        // 回归：建议桥 confirm 时用户原句点名了计划（多 ACTIVE 场景）→ planId 直给，
        // 不能无视点名全塞候选让用户再选一遍
        String context = "{\"pendingWorkflowSuggestion\":{"
                + "\"workflowType\":\"LEARNING_ASSIST\","
                + "\"reason\":\"需要辅助拆解\","
                + "\"initialMessage\":\"C++ 计划的第三阶段太难了，帮我拆解一下\","
                + "\"risk\":\"MEDIUM\"}}";
        when(runMapper.selectById(1L)).thenReturn(
                run(1L, 100L, "WAITING_WORKFLOW_CONFIRM", context));
        when(runMapper.update(any(AiAgentRun.class), any())).thenReturn(1);
        when(learningPlansService.listByUser(100L)).thenReturn(List.of(
                activePlan(1L, "C++ 学习计划"), activePlan(2L, "Redis 学习计划")));
        when(learningPlansService.matchPlansByMessage(100L, "C++ 计划的第三阶段太难了，帮我拆解一下"))
                .thenReturn(List.of(activePlan(1L, "C++ 学习计划")));
        AiWorkflowRunVO vo = new AiWorkflowRunVO();
        vo.setId("wf-assist");
        when(aiWorkflowRunService.createLearningAssistWorkflow(any(AiWorkflowLearningAssistDTO.class)))
                .thenReturn(vo);

        AiWorkflowRunVO result = service.confirm(1L, null);

        assertThat(result.getId()).isEqualTo("wf-assist");
        ArgumentCaptor<AiWorkflowLearningAssistDTO> dtoCaptor =
                ArgumentCaptor.forClass(AiWorkflowLearningAssistDTO.class);
        verify(aiWorkflowRunService).createLearningAssistWorkflow(dtoCaptor.capture());
        AiWorkflowLearningAssistDTO dto = dtoCaptor.getValue();
        assertThat(dto.getPlanId()).isEqualTo(1L);
        assertThat(dto.getRequest()).isEqualTo("C++ 计划的第三阶段太难了，帮我拆解一下");
        assertThat(dto.getHandoffReason()).isEqualTo("需要辅助拆解");
    }

    @Test
    void confirmAssistSuggestionWithAmbiguousMessageFallsBackToCandidates() {
        // 消息没点名/点名歧义 → 保持原兜底：候选列表让用户选（不能退化成无 planId 直接失败）
        String context = "{\"pendingWorkflowSuggestion\":{"
                + "\"workflowType\":\"LEARNING_ASSIST\","
                + "\"reason\":\"需要辅助拆解\","
                + "\"initialMessage\":\"我感觉学习有点难，帮我拆解一下\","
                + "\"risk\":\"MEDIUM\"}}";
        when(runMapper.selectById(1L)).thenReturn(
                run(1L, 100L, "WAITING_WORKFLOW_CONFIRM", context));
        when(runMapper.update(any(AiAgentRun.class), any())).thenReturn(1);
        when(learningPlansService.listByUser(100L)).thenReturn(List.of(
                activePlan(1L, "C++ 学习计划"), activePlan(2L, "Redis 学习计划")));
        when(aiWorkflowRunService.createLearningAssistWorkflow(any(AiWorkflowLearningAssistDTO.class)))
                .thenReturn(new AiWorkflowRunVO());

        service.confirm(1L, null);

        ArgumentCaptor<AiWorkflowLearningAssistDTO> dtoCaptor =
                ArgumentCaptor.forClass(AiWorkflowLearningAssistDTO.class);
        verify(aiWorkflowRunService).createLearningAssistWorkflow(dtoCaptor.capture());
        AiWorkflowLearningAssistDTO dto = dtoCaptor.getValue();
        assertThat(dto.getPlanId()).isNull();
        assertThat(dto.getCandidates()).hasSize(2);
        assertThat(dto.getHandoffReason()).isNull();
    }

    @Test
    void confirmWithSameIdempotencyKeyReturnsCachedResult() {
        AiWorkflowRunVO cached = new AiWorkflowRunVO();
        cached.setId("wf-1");
        when(idempotency.fingerprint(anyString(), anyString())).thenReturn("fp");
        when(idempotency.get(eq(100L), eq(1L), eq("confirm"), eq("key-1"), eq("fp")))
                .thenReturn(cached);

        AiWorkflowRunVO result = service.confirm(1L, "key-1");

        assertThat(result.getId()).isEqualTo("wf-1");
        // 幂等命中：不创建 Workflow、不拿锁
        verify(aiWorkflowRunService, never()).createLearningPlanWorkflow(any());
        verify(actionLock, never()).acquireOrThrow(anyString(), any());
    }

    @Test
    void confirmRejectsWhenRunNotWaiting() {
        when(runMapper.selectById(1L)).thenReturn(
                run(1L, 100L, "COMPLETED", null));

        assertThatThrownBy(() -> service.confirm(1L, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已被处理或已过期");
    }

    @Test
    void confirmFailsWhenCasConsumedConcurrently() {
        // 两个不同 key 并发：第一个 CAS 成功，第二个 CAS 影响行数为 0（已被消费）
        when(runMapper.selectById(1L)).thenReturn(
                run(1L, 100L, "WAITING_WORKFLOW_CONFIRM", SUGGESTION_CONTEXT));
        when(runMapper.update(any(AiAgentRun.class), any())).thenReturn(0);

        assertThatThrownBy(() -> service.confirm(1L, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已被处理");
        verify(aiWorkflowRunService, never()).createLearningPlanWorkflow(any());
    }

    @Test
    void confirmBlockedWhenActiveWorkflowExists() {
        when(runMapper.selectById(1L)).thenReturn(
                run(1L, 100L, "WAITING_WORKFLOW_CONFIRM", SUGGESTION_CONTEXT));
        doThrow(new BusinessException(BlogConstants.ErrorCode.CONFLICT, "当前已有正在进行的 Workflow"))
                .when(workflowRunManager).checkActiveWorkflowConflict(any(), any());

        assertThatThrownBy(() -> service.confirm(1L, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Workflow");
        // 冲突检查在 CAS 之前：状态未被消耗
        verify(runMapper, never()).update(any(AiAgentRun.class), any());
    }

    @Test
    void confirmRejectsForeignRun() {
        when(runMapper.selectById(1L)).thenReturn(
                run(1L, 999L, "WAITING_WORKFLOW_CONFIRM", SUGGESTION_CONTEXT));

        assertThatThrownBy(() -> service.confirm(1L, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权");
    }

    @Test
    void confirmPropagatesWorkflowCreationFailure() {
        when(runMapper.selectById(1L)).thenReturn(
                run(1L, 100L, "WAITING_WORKFLOW_CONFIRM", SUGGESTION_CONTEXT));
        when(runMapper.update(any(AiAgentRun.class), any())).thenReturn(1);
        when(aiWorkflowRunService.createLearningPlanWorkflow(any()))
                .thenThrow(new RuntimeException("Workflow 创建失败"));

        assertThatThrownBy(() -> service.confirm(1L, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("创建失败");
        // 事务回滚（Spring 管理）后 run 回到 WAITING；这里验证异常传播
    }

    @Test
    void getSuggestionReturnsPendingSuggestion() {
        when(runMapper.selectById(1L)).thenReturn(
                run(1L, 100L, "WAITING_WORKFLOW_CONFIRM", SUGGESTION_CONTEXT));

        AgentRunSuggestionView view = service.getSuggestion(1L);

        assertThat(view.status()).isEqualTo("WAITING_WORKFLOW_CONFIRM");
        assertThat(view.suggestion()).isNotNull();
        assertThat(view.suggestion().workflowType()).isEqualTo("LEARNING_PLAN");
    }

    @Test
    void getSuggestionReturnsNullWhenConsumed() {
        when(runMapper.selectById(1L)).thenReturn(
                run(1L, 100L, "COMPLETED", null));

        AgentRunSuggestionView view = service.getSuggestion(1L);

        assertThat(view.status()).isEqualTo("COMPLETED");
        assertThat(view.suggestion()).isNull();
    }

    @Test
    void cancelMarksCancelled() {
        when(runMapper.selectById(1L)).thenReturn(
                run(1L, 100L, "WAITING_WORKFLOW_CONFIRM", SUGGESTION_CONTEXT));
        when(runMapper.update(any(AiAgentRun.class), any())).thenReturn(1);

        String message = service.cancel(1L);

        assertThat(message).contains("已取消");
        ArgumentCaptor<AiAgentRun> captor = ArgumentCaptor.forClass(AiAgentRun.class);
        verify(runMapper).update(captor.capture(), any());
        assertThat(captor.getValue().getStatus()).isEqualTo("CANCELLED");
        verify(aiWorkflowRunService, never()).createLearningPlanWorkflow(any());
    }

    @Test
    void cancelAfterConfirmReturnsConflict() {
        when(runMapper.selectById(1L)).thenReturn(
                run(1L, 100L, "COMPLETED", null));

        assertThatThrownBy(() -> service.cancel(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已被处理或已过期");
    }

    @Test
    void confirmArticleOptimizeSuggestionCreatesOptimizeWorkflow() {
        // V2.5：文章类建议走 articleId + instruction → createArticleOptimizeWorkflow
        String context = "{\"pendingWorkflowSuggestion\":{"
                + "\"workflowType\":\"OPTIMIZE_ARTICLE\","
                + "\"reason\":\"这篇文章缺少小标题\","
                + "\"initialMessage\":\"帮我看看这篇文章还能怎么改\","
                + "\"risk\":\"MEDIUM\","
                + "\"articleId\":\"12\"}}";
        when(runMapper.selectById(1L)).thenReturn(
                run(1L, 100L, "WAITING_WORKFLOW_CONFIRM", context));
        when(runMapper.update(any(AiAgentRun.class), any())).thenReturn(1);

        AiWorkflowRunVO vo = new AiWorkflowRunVO();
        vo.setId("wf-opt-1");
        when(aiWorkflowRunService.createArticleOptimizeWorkflow(any(com.hailin.blogsystem.entity.dto.AiWorkflowOptimizeArticleDTO.class)))
                .thenReturn(vo);

        AiWorkflowRunVO result = service.confirm(1L, null);

        assertThat(result.getId()).isEqualTo("wf-opt-1");
        ArgumentCaptor<com.hailin.blogsystem.entity.dto.AiWorkflowOptimizeArticleDTO> dtoCaptor =
                ArgumentCaptor.forClass(com.hailin.blogsystem.entity.dto.AiWorkflowOptimizeArticleDTO.class);
        verify(aiWorkflowRunService).createArticleOptimizeWorkflow(dtoCaptor.capture());
        assertThat(dtoCaptor.getValue().getArticleId()).isEqualTo(12L);
        assertThat(dtoCaptor.getValue().getInstruction()).isEqualTo("帮我看看这篇文章还能怎么改");
        assertThat(dtoCaptor.getValue().getConversationId()).isEqualTo(200L);
    }

    @Test
    void confirmArticleOptimizeSuggestionWithoutArticleIdFails() {
        // V2.5：建议快照缺 articleId（脏数据）→ 拒绝启动，不猜
        String context = "{\"pendingWorkflowSuggestion\":{"
                + "\"workflowType\":\"OPTIMIZE_ARTICLE\","
                + "\"reason\":\"缺少文章 ID\","
                + "\"initialMessage\":\"帮我看看这篇文章\"}}";
        when(runMapper.selectById(1L)).thenReturn(
                run(1L, 100L, "WAITING_WORKFLOW_CONFIRM", context));
        when(runMapper.update(any(AiAgentRun.class), any())).thenReturn(1);

        assertThatThrownBy(() -> service.confirm(1L, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("缺少文章 ID");
        verify(aiWorkflowRunService, never()).createArticleOptimizeWorkflow(any());
    }

    private AiAgentRun run(Long id, Long userId, String status, String contextJson) {
        AiAgentRun run = new AiAgentRun();
        run.setId(id);
        run.setUserId(userId);
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
}
