package com.hailin.blogsystem.ai.workflow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.AiAgentStep;
import com.hailin.blogsystem.entity.AiMessages;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.mapper.AiAgentStepMapper;
import com.hailin.blogsystem.mapper.AiMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 学习进度 Workflow 的自然语言承接 handoff：
 * 「先分析我的 C++ 计划」→ Agent 给建议 →「按你说的优化」→ Workflow 需要看到上一轮建议。
 *
 * 只读、机会性注入：取不到就返回 empty，主流程仍按计划锚定位目标，不阻塞 Workflow 创建。
 */
@Component
@RequiredArgsConstructor
public class LearningProgressHandoffResolver {

    private static final int RECENT_AGENT_MESSAGE_LIMIT = 5;

    private final AiMessageMapper messageMapper;
    private final AiAgentRunMapper runMapper;
    private final AiAgentStepMapper stepMapper;

    public Optional<Handoff> resolve(Long sessionId, Long userId, String message) {
        if (sessionId == null || userId == null || !hasReferenceSignal(message)) {
            return Optional.empty();
        }

        List<AiMessages> recentAgentMessages = messageMapper.selectList(new LambdaQueryWrapper<AiMessages>()
                .eq(AiMessages::getSessionId, sessionId)
                .eq(AiMessages::getRole, "assistant")
                .isNotNull(AiMessages::getAgentRunId)
                .orderByDesc(AiMessages::getCreatedAt)
                .orderByDesc(AiMessages::getId)
                .last("LIMIT " + RECENT_AGENT_MESSAGE_LIMIT));

        for (AiMessages assistantMessage : recentAgentMessages) {
            AiAgentRun run = runMapper.selectById(assistantMessage.getAgentRunId());
            if (!isUsableRun(run, sessionId, userId) || !hasLearningDashboardStep(run.getId())) {
                continue;
            }
            return Optional.of(new Handoff(run.getId(), run.getFinalAnswer().trim()));
        }
        return Optional.empty();
    }

    private boolean hasReferenceSignal(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String text = message.trim();
        return text.contains("你说的")
                || text.contains("你刚才")
                || text.contains("你刚刚")
                || text.contains("刚才的建议")
                || text.contains("刚刚的建议")
                || text.contains("按刚才")
                || text.contains("按刚刚")
                || text.contains("按照刚才")
                || text.contains("按照刚刚")
                || text.contains("按照你的建议")
                || text.contains("按你的建议")
                || text.contains("照你说")
                || text.contains("照刚才");
    }

    private boolean isUsableRun(AiAgentRun run, Long sessionId, Long userId) {
        return run != null
                && userId.equals(run.getUserId())
                && sessionId.equals(run.getSessionId())
                && AiAgentRunStatus.COMPLETED.name().equals(run.getStatus())
                && run.getFinalAnswer() != null
                && !run.getFinalAnswer().isBlank();
    }

    private boolean hasLearningDashboardStep(Long runId) {
        if (runId == null) {
            return false;
        }
        Long count = stepMapper.selectCount(new LambdaQueryWrapper<AiAgentStep>()
                .eq(AiAgentStep::getAgentRunId, runId)
                .eq(AiAgentStep::getActionType, AgentStepActionType.QUERY_LEARNING_DASHBOARD.name())
                .eq(AiAgentStep::getStatus, "SUCCESS"));
        return count != null && count > 0;
    }

    public record Handoff(Long sourceAgentRunId, String suggestedDirection) {
    }
}
