package com.hailin.blogsystem.ai.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.AiAgentStep;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import com.hailin.blogsystem.entity.vo.AgentRunDetailVO;
import com.hailin.blogsystem.entity.vo.AgentRunDevDetailVO;
import com.hailin.blogsystem.entity.vo.AgentRunSummaryVO;
import com.hailin.blogsystem.entity.vo.AgentStepRawVO;
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

    /**
     * V4 第一刀·开发者通道：完整 step 列表（含 inputJson / outputJson 原始列）。
     *
     * 与 {@link #listSteps(Long)} 同一数据源，区别是**不做派生、不脱敏**——仅供开发者审计，
     * 调用方必须已过白名单门禁（见 AgentRunDevController）。数据范围仍是「自己的 run」。
     */
    public List<AgentStepRawVO> listRawSteps(Long agentRunId) {
        Long userId = requireLogin();
        getOwnedRun(agentRunId, userId);

        return stepMapper.selectList(new LambdaQueryWrapper<AiAgentStep>()
                        .eq(AiAgentStep::getAgentRunId, agentRunId)
                        .orderByAsc(AiAgentStep::getStepNo))
                .stream()
                .map(this::toRawStep)
                .toList();
    }

    /**
     * V4 第一刀·开发者通道：安全摘要 + 原始 contextJson（observations）。
     *
     * 组合而非合并：摘要与原始材料字段分开摆放，避免使用者忘记哪一份可信。
     */
    public AgentRunDevDetailVO getDevDetail(Long agentRunId) {
        Long userId = requireLogin();
        AiAgentRun run = getOwnedRun(agentRunId, userId);

        AgentRunDevDetailVO vo = new AgentRunDevDetailVO();
        vo.setRun(toDetail(run));
        vo.setContextJson(run.getContextJson());
        return vo;
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
        // V3.13：计划（run 级）——读取失败按空计划处理并记录日志，不影响 steps 恢复
        vo.setPlan(parsePlan(run.getPlanJson()));
        if (AiAgentRunStatus.WAITING_WORKFLOW_CONFIRM.name().equals(run.getStatus())) {
            vo.setPendingWorkflowSuggestion(parseSuggestion(run.getContextJson()));
        }
        return vo;
    }

    /**
     * V3.13：计划列 → 列表。读取失败按空计划处理并记录日志（计划是展示增强，不阻塞 steps 恢复）。
     */
    private List<String> parsePlan(String planJson) {
        if (planJson == null || planJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(planJson, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("Agent Run plan_json 解析失败（按空计划处理）: {}", planJson);
            return null;
        }
    }

    private AgentStepVO toStep(AiAgentStep step) {
        AgentStepVO vo = new AgentStepVO();
        vo.setStepNo(step.getStepNo());
        vo.setActionType(step.getActionType());
        String displayStatus = displayStatus(step);
        vo.setStatus(displayStatus);
        vo.setErrorMessage(step.getErrorMessage());
        vo.setDurationMs(step.getDurationMs());
        // V3.10：思考摘要（与实时 AGENT_STEP 事件一致，刷新前后行文本不串味；可空）
        vo.setThoughtSummary(step.getThoughtSummary());
        // 展示文案与实时 AGENT_STEP 事件一致（刷新前后思考面板不串味）
        vo.setMessage(switch (displayStatus) {
            case "SUCCESS" -> AgentStepLabelSupport.completedMessage(
                    step.getActionType(), step.getInputJson(), objectMapper);
            case "SKIPPED" -> AgentStepLabelSupport.skippedMessage(
                    step.getActionType(), step.getErrorMessage());
            default -> AgentStepLabelSupport.failedMessage(step.getActionType(), step.getErrorMessage());
        });
        vo.setSummary(parseSummary(step.getOutputJson()));
        vo.setCreatedAt(step.getCreatedAt());
        return vo;
    }

    /**
     * 历史兼容：重复查询最早落成 FAILED，但语义上是系统主动跳过。
     * 普通展示层归一为 SKIPPED；开发者 raw 接口仍暴露 DB 原值。
     */
    private String displayStatus(AiAgentStep step) {
        if ("FAILED".equals(step.getStatus())
                && step.getErrorMessage() != null
                && step.getErrorMessage().startsWith(AgentStepLabelSupport.DUPLICATE_QUERY_SKIP_PREFIX)) {
            return "SKIPPED";
        }
        return step.getStatus();
    }

    private AgentStepRawVO toRawStep(AiAgentStep step) {
        AgentStepRawVO vo = new AgentStepRawVO();
        vo.setStepNo(step.getStepNo());
        vo.setActionType(step.getActionType());
        vo.setStatus(step.getStatus());
        vo.setErrorMessage(step.getErrorMessage());
        vo.setDurationMs(step.getDurationMs());
        vo.setInputJson(step.getInputJson());
        vo.setOutputJson(step.getOutputJson());
        vo.setThoughtSummary(step.getThoughtSummary());
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
