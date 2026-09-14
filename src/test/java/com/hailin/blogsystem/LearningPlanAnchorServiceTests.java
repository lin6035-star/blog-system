package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.agent.LearningPlanAnchorService;
import com.hailin.blogsystem.entity.AiSessions;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.service.AiSessionService;
import com.hailin.blogsystem.service.LearningPlansService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话学习计划锚读写测试（V4.x）。
 *
 * 这个锚解决的问题：分类器只接收当前这一条消息（不读对话历史），多轮里第二轮省略主语会断片。
 * mark：覆盖式写点（session 存在才写；标题取数据库权威，计划已删时容忍 null）。
 * resolve：会话归属 + 锚存在 + 计划存在 + 计划归属，四条件全过才返回，否则 null（宁不猜）。
 */
class LearningPlanAnchorServiceTests {

    private AiSessionService aiSessionService;
    private LearningPlansService learningPlansService;
    private LearningPlanAnchorService service;

    private static AiSessions session(Long id, Long userId, Long planId) {
        AiSessions session = new AiSessions();
        session.setId(id);
        session.setUserId(userId);
        session.setLastLearningPlanId(planId);
        return session;
    }

    private static LearningPlans plan(Long id, Long userId, String title) {
        LearningPlans plan = new LearningPlans();
        plan.setId(id);
        plan.setUserId(userId);
        plan.setTitle(title);
        return plan;
    }

    @BeforeEach
    void setUp() {
        aiSessionService = mock(AiSessionService.class);
        learningPlansService = mock(LearningPlansService.class);
        service = new LearningPlanAnchorService(aiSessionService, learningPlansService);
    }

    @Test
    void markWritesAnchorWithAuthoritativeTitle() {
        when(aiSessionService.getById(10L)).thenReturn(session(10L, 100L, null));
        when(learningPlansService.getById(1L))
                .thenReturn(plan(1L, 100L, "C++ 系统学习与工程化实践计划"));

        service.mark(10L, 1L, LearningPlanAnchorService.SOURCE_CLASSIFIER);

        ArgumentCaptor<AiSessions> captor = ArgumentCaptor.forClass(AiSessions.class);
        verify(aiSessionService).updateById(captor.capture());
        assertThat(captor.getValue().getLastLearningPlanId()).isEqualTo(1L);
        assertThat(captor.getValue().getLastLearningPlanTitle())
                .isEqualTo("C++ 系统学习与工程化实践计划");
        assertThat(captor.getValue().getLastLearningPlanSource())
                .isEqualTo(LearningPlanAnchorService.SOURCE_CLASSIFIER);
        assertThat(captor.getValue().getLastLearningPlanUpdatedAt()).isNotNull();
    }

    @Test
    void markToleratesDeletedPlan() {
        // 计划已删：本体 id 仍写入（resolve 会判失效），标题容忍 null
        when(aiSessionService.getById(10L)).thenReturn(session(10L, 100L, null));
        when(learningPlansService.getById(1L)).thenReturn(null);

        service.mark(10L, 1L, LearningPlanAnchorService.SOURCE_BACKEND_MATCH);

        ArgumentCaptor<AiSessions> captor = ArgumentCaptor.forClass(AiSessions.class);
        verify(aiSessionService).updateById(captor.capture());
        assertThat(captor.getValue().getLastLearningPlanId()).isEqualTo(1L);
        assertThat(captor.getValue().getLastLearningPlanTitle()).isNull();
    }

    @Test
    void markSkipsWhenSessionMissing() {
        when(aiSessionService.getById(99L)).thenReturn(null);

        service.mark(99L, 1L, LearningPlanAnchorService.SOURCE_CLASSIFIER);

        verify(aiSessionService, never()).updateById(any());
    }

    @Test
    void resolveReturnsPlanWhenEverythingValid() {
        when(aiSessionService.getById(10L)).thenReturn(session(10L, 100L, 1L));
        when(learningPlansService.getById(1L)).thenReturn(plan(1L, 100L, "C++ 计划"));

        LearningPlans resolved = service.resolve(10L, 100L);

        assertThat(resolved).isNotNull();
        assertThat(resolved.getId()).isEqualTo(1L);
        assertThat(resolved.getTitle()).isEqualTo("C++ 计划");
    }

    @Test
    void resolveReturnsNullWhenSessionNotOwned() {
        // 防越权读他人会话的锚
        when(aiSessionService.getById(10L)).thenReturn(session(10L, 999L, 1L));

        assertThat(service.resolve(10L, 100L)).isNull();
    }

    @Test
    void resolveReturnsNullWhenNoAnchor() {
        when(aiSessionService.getById(10L)).thenReturn(session(10L, 100L, null));

        assertThat(service.resolve(10L, 100L)).isNull();
    }

    @Test
    void resolveReturnsNullWhenPlanDeletedOrNotOwned() {
        when(aiSessionService.getById(10L)).thenReturn(session(10L, 100L, 1L));

        when(learningPlansService.getById(1L)).thenReturn(null);
        assertThat(service.resolve(10L, 100L)).isNull();

        when(learningPlansService.getById(1L)).thenReturn(plan(1L, 999L, "别人的计划"));
        assertThat(service.resolve(10L, 100L)).isNull();
    }

    @Test
    void resolveReturnsNullOnBlankArgs() {
        assertThat(service.resolve(null, 100L)).isNull();
        assertThat(service.resolve(10L, null)).isNull();
    }
}
