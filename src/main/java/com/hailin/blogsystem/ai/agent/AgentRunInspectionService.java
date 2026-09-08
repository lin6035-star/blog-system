package com.hailin.blogsystem.ai.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.AiAgentStep;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import com.hailin.blogsystem.entity.vo.AgentRunDetailVO;
import com.hailin.blogsystem.entity.vo.AgentRunSummaryVO;
import com.hailin.blogsystem.entity.vo.AgentStepVO;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.exception.BusinessException;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.mapper.AiAgentStepMapper;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Agent Run inspection 层（V2.2，只读）。
 *
 * 把"跑过什么、卡在哪、最后为什么停"稳定读出来：
 * - 单 run 详情（安全摘要，不含完整 prompt / 内部脏上下文）
 * - 步骤列表（按 stepNo 排序，observation 摘要）
 * - 会话历史列表（分页，排查用户最近在干什么）
 *
 * 边界：只读无写动作；只查当前用户自己的 run；不影响 workflow-suggestion confirm/cancel。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentRunInspectionService {

    private final AiAgentRunMapper runMapper;
    private final AiAgentStepMapper stepMapper;
    private final ObjectMapper objectMapper;

    public AgentRunDetailVO getDetail(Long agentRunId) {
        Long userId = requireLogin();
        AiAgentRun run = getOwnedRun(agentRunId, userId);
        return toDetail(run);
    }

    public List<AgentStepVO> listSteps(Long agentRunId) {
        Long userId = requireLogin();
        getOwnedRun(agentRunId, userId);

        return stepMapper.selectList(new LambdaQueryWrapper<AiAgentStep>()
                        .eq(AiAgentStep::getAgentRunId, agentRunId)
                        .orderByAsc(AiAgentStep::getStepNo))
                .stream()
                .map(this::toStep)
                .toList();
    }

    public PageVO<AgentRunSummaryVO> listRuns(Long sessionId, Long page, Long pageSize) {
        Long userId = requireLogin();
        long safePage = page == null || page < 1 ? 1 : page;
        long safeSize = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 50);

        Page<AiAgentRun> pageResult = runMapper.selectPage(
                new Page<>(safePage, safeSize),
                new LambdaQueryWrapper<AiAgentRun>()
                        .eq(AiAgentRun::getUserId, userId)
                        .eq(sessionId != null, AiAgentRun::getSessionId, sessionId)
                        .orderByDesc(AiAgentRun::getCreatedAt)
        );

        List<AgentRunSummaryVO> list = pageResult.getRecords().stream()
                .map(this::toSummary)
                .toList();
        return new PageVO<>(list, pageResult.getTotal(), safePage, safeSize);
    }

    private AgentRunDetailVO toDetail(AiAgentRun run) {
        AgentRunDetailVO vo = new AgentRunDetailVO();
        vo.setId(run.getId());
        vo.setSessionId(run.getSessionId());
        vo.setGoal(run.getGoal());
        vo.setStatus(run.getStatus());
        vo.setCurrentStep(run.getCurrentStep());
        vo.setUsedSteps(run.getUsedSteps());
        vo.setMaxSteps(run.getMaxSteps());
        vo.setFinalAnswer(run.getFinalAnswer());
        vo.setErrorMessage(run.getErrorMessage());
        vo.setCreatedAt(run.getCreatedAt());
        vo.setUpdatedAt(run.getUpdatedAt());
        if (AiAgentRunStatus.WAITING_WORKFLOW_CONFIRM.name().equals(run.getStatus())) {
            vo.setPendingWorkflowSuggestion(parseSuggestion(run.getContextJson()));
        }
        return vo;
    }

    private AgentStepVO toStep(AiAgentStep step) {
        AgentStepVO vo = new AgentStepVO();
        vo.setStepNo(step.getStepNo());
        vo.setActionType(step.getActionType());
        vo.setStatus(step.getStatus());
        vo.setErrorMessage(step.getErrorMessage());
        vo.setDurationMs(step.getDurationMs());
        // V3.10：思考摘要（与实时 AGENT_STEP 事件一致，刷新前后行文本不串味；可空）
        vo.setThoughtSummary(step.getThoughtSummary());
        // 展示文案与实时 AGENT_STEP 事件一致（刷新前后思考面板不串味）
        vo.setMessage("SUCCESS".equals(step.getStatus())
                ? AgentStepLabelSupport.completedMessage(
                        step.getActionType(), step.getInputJson(), objectMapper)
                : AgentStepLabelSupport.failedMessage(step.getActionType()));
        vo.setSummary(parseSummary(step.getOutputJson()));
        vo.setCreatedAt(step.getCreatedAt());
        return vo;
    }

    private AgentRunSummaryVO toSummary(AiAgentRun run) {
        AgentRunSummaryVO vo = new AgentRunSummaryVO();
        vo.setId(run.getId());
        vo.setStatus(run.getStatus());
        vo.setGoal(run.getGoal());
        vo.setUsedSteps(run.getUsedSteps());
        vo.setMaxSteps(run.getMaxSteps());
        vo.setCreatedAt(run.getCreatedAt());
        vo.setUpdatedAt(run.getUpdatedAt());
        return vo;
    }

    /** outputJson 为 {"summary": "..."}，取 summary 字段 */
    private String parseSummary(String outputJson) {
        if (outputJson == null || outputJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(outputJson);
            return root.path("summary").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private AgentWorkflowSuggestion parseSuggestion(String contextJson) {
        if (contextJson == null || contextJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(contextJson);
            JsonNode node = root.path("pendingWorkflowSuggestion");
            if (node.isMissingNode() || !node.isObject()) {
                return null;
            }
            return new AgentWorkflowSuggestion(
                    node.path("workflowType").asText(null),
                    node.path("reason").asText(null),
                    node.path("initialMessage").asText(null),
                    node.path("risk").asText(null),
                    node.path("articleId").asText(null)
            );
        } catch (Exception e) {
            return null;
        }
    }

    private AiAgentRun getOwnedRun(Long agentRunId, Long userId) {
        AiAgentRun run = runMapper.selectById(agentRunId);
        if (run == null) {
            throw new BusinessException(
                    BlogConstants.ErrorCode.NOT_FOUND,
                    "Agent Run 不存在"
            );
        }
        if (userId == null || !userId.equals(run.getUserId())) {
            throw new BusinessException(
                    BlogConstants.ErrorCode.FORBIDDEN,
                    "不存在或无权访问"
            );
        }
        return run;
    }

    private Long requireLogin() {
        Long userId = UserContext.get();
        if (userId == null) {
            throw new BusinessException(
                    BlogConstants.ErrorCode.UNAUTHORIZED,
                    "请先登录"
            );
        }
        return userId;
    }
}
