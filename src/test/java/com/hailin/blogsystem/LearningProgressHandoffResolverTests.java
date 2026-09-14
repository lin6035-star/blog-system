package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.workflow.LearningProgressHandoffResolver;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.AiMessages;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.mapper.AiAgentStepMapper;
import com.hailin.blogsystem.mapper.AiMessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LearningProgressHandoffResolverTests {

    private AiMessageMapper messageMapper;
    private AiAgentRunMapper runMapper;
    private AiAgentStepMapper stepMapper;
    private LearningProgressHandoffResolver resolver;

    @BeforeEach
    void setUp() {
        messageMapper = mock(AiMessageMapper.class);
        runMapper = mock(AiAgentRunMapper.class);
        stepMapper = mock(AiAgentStepMapper.class);
        resolver = new LearningProgressHandoffResolver(messageMapper, runMapper, stepMapper);
    }

    @Test
    void resolvesLatestCompletedLearningAgentAnswerForReferenceRequest() {
        AiMessages message = assistantAgentMessage(10L);
        when(messageMapper.selectList(any())).thenReturn(List.of(message));
        when(runMapper.selectById(10L)).thenReturn(agentRun(10L, AiAgentRunStatus.COMPLETED.name(),
                "建议把 C++ 学习计划收敛到学校课程，先保留课堂作业和考试复习。"));
        when(stepMapper.selectCount(any())).thenReturn(1L);

        Optional<LearningProgressHandoffResolver.Handoff> handoff = resolver.resolve(
                200L,
                100L,
                "可以，就按照你说的优化建议优化一下我的计划"
        );

        assertThat(handoff).isPresent();
        assertThat(handoff.get().sourceAgentRunId()).isEqualTo(10L);
        assertThat(handoff.get().suggestedDirection()).contains("学校课程");
    }

    @Test
    void ignoresNonLearningAgentRunEvenWhenItHasFinalAnswer() {
        AiMessages message = assistantAgentMessage(11L);
        when(messageMapper.selectList(any())).thenReturn(List.of(message));
        when(runMapper.selectById(11L)).thenReturn(agentRun(11L, AiAgentRunStatus.COMPLETED.name(),
                "这篇文章建议增加小标题。"));
        when(stepMapper.selectCount(any())).thenReturn(0L);

        Optional<LearningProgressHandoffResolver.Handoff> handoff = resolver.resolve(
                200L,
                100L,
                "按你说的优化一下我的计划"
        );

        assertThat(handoff).isEmpty();
    }

    @Test
    void ignoresRequestWithoutReferenceSignal() {
        Optional<LearningProgressHandoffResolver.Handoff> handoff = resolver.resolve(
                200L,
                100L,
                "帮我把 C++ 计划压缩到两周"
        );

        assertThat(handoff).isEmpty();
    }

    private AiMessages assistantAgentMessage(Long agentRunId) {
        AiMessages message = new AiMessages();
        message.setSessionId(200L);
        message.setRole("assistant");
        message.setAgentRunId(agentRunId);
        message.setContent("assistant answer");
        message.setCreatedAt(LocalDateTime.now());
        return message;
    }

    private AiAgentRun agentRun(Long id, String status, String finalAnswer) {
        AiAgentRun run = new AiAgentRun();
        run.setId(id);
        run.setUserId(100L);
        run.setSessionId(200L);
        run.setStatus(status);
        run.setFinalAnswer(finalAnswer);
        return run;
    }
}
