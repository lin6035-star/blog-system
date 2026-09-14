package com.hailin.blogsystem.ai.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.workflow.WorkflowActionIdempotency;
import com.hailin.blogsystem.ai.workflow.WorkflowActionLock;
import com.hailin.blogsystem.ai.workflow.WorkflowRunManager;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.AiMessages;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import com.hailin.blogsystem.entity.dto.AiWorkflowLearningAssistDTO;
import com.hailin.blogsystem.entity.dto.AiWorkflowLearningPlanDTO;
import com.hailin.blogsystem.entity.dto.AiWorkflowLearningProgressDTO;
import com.hailin.blogsystem.entity.dto.AiWorkflowOptimizeArticleDTO;
import com.hailin.blogsystem.entity.vo.AiWorkflowRunVO;
import com.hailin.blogsystem.exception.BusinessException;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.mapper.AiMessageMapper;
import com.hailin.blogsystem.service.AiWorkflowRunService;
import com.hailin.blogsystem.service.LearningPlansService;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent Run Workflow 建议的确认 / 取消（V2.1）。
 *
 * 安全边界：
 * - Agent 只能建议学习类 Workflow（allowlist），confirm 时二次校验
 * - 确认前不启动任何 Workflow；确认 = 用户明确授权后复用现有 Workflow Runtime
 * - 并发保护双保险：Redis 锁（SETNX + Lua 释放 + fail-open）+ DB 状态 CAS
 *   （WAITING_WORKFLOW_CONFIRM -> CONFIRMING 一次性消费，失败回滚可重试）
 * - Idempotency-Key 防同请求重试重复创建（复用 WorkflowActionIdempotency，
 *   runId 空间与 Workflow 不同，不冲突）
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentRunSuggestionService {

    private static final String ACTION_CONFIRM = "confirm";

    private static final Set<String> ALLOWED_WORKFLOW_TYPES = Set.of(
            "LEARNING_PLAN", "LEARNING_PROGRESS", "LEARNING_ASSIST",
            "OPTIMIZE_ARTICLE"
    );

    private final AiAgentRunMapper runMapper;
    private final AiMessageMapper aiMessageMapper;
    private final ObjectMapper objectMapper;
    private final WorkflowActionLock actionLock;
    private final WorkflowActionIdempotency idempotency;
    private final AiWorkflowRunService aiWorkflowRunService;
    private final WorkflowRunManager workflowRunManager;
    private final LearningPlansService learningPlansService;
    /** V3.12：结论锚写点 B（用户确认时刻）。 */
    private final ArticleSessionAnchorService anchorService;

    /**
     * 确认建议：校验归属/状态/冲突 → 状态 CAS 一次性消费 → 复用现有 Workflow 创建路径。
     * 整体 @Transactional：createXxxWorkflow 与状态更新同事务，失败整体回滚（run 自然回到 WAITING 可重试）。
     */
    @Transactional
    public AiWorkflowRunVO confirm(Long agentRunId, String idempotencyKey) {
        Long userId = requireLogin();
        log.info("Agent 建议确认请求进入: agentRunId={}, userId={}", agentRunId, userId);

        // 幂等：同 key + 同 fingerprint 直接返回上次结果（防双击重复创建）
        String fingerprint = idempotency.fingerprint(ACTION_CONFIRM, String.valueOf(agentRunId));
        if (notBlank(idempotencyKey)) {
            AiWorkflowRunVO cached = idempotency.get(userId, agentRunId, ACTION_CONFIRM, idempotencyKey, fingerprint);
            if (cached != null) {
                log.info("Agent 建议确认幂等命中: agentRunId={}", agentRunId);
                return cached;
            }
        }

        WorkflowActionLock.LockHandle lock = actionLock.acquireOrThrow(
                RedisConstants.AI_AGENT_RUN_ACTION_LOCK_KEY_PREFIX, agentRunId);
        log.info("Agent 建议确认已获取锁: agentRunId={}", agentRunId);
        try {
            AiWorkflowRunVO vo = confirmWithLock(agentRunId, userId);
            log.info("Agent 建议确认完成: agentRunId={}, workflowRunId={}", agentRunId, vo.getId());
            if (notBlank(idempotencyKey)) {
                idempotency.save(userId, agentRunId, ACTION_CONFIRM, idempotencyKey, fingerprint, vo);
            }
            return vo;
        } finally {
            actionLock.release(lock);
            log.info("Agent 建议确认锁已释放: agentRunId={}", agentRunId);
        }
    }

    /**
     * 查询建议快照（前端历史消息恢复建议卡）：仅 WAITING_WORKFLOW_CONFIRM 返回 suggestion。
     */
    public AgentRunSuggestionView getSuggestion(Long agentRunId) {
        Long userId = requireLogin();
        AiAgentRun run = getOwnedRun(agentRunId, userId);
        if (run.getStatus() == null
                || !AiAgentRunStatus.WAITING_WORKFLOW_CONFIRM.name().equals(run.getStatus())) {
            return new AgentRunSuggestionView(run.getStatus(), null);
        }
        return new AgentRunSuggestionView(run.getStatus(), readSuggestion(run));
    }

    /**
     * 取消建议：WAITING_WORKFLOW_CONFIRM -> CANCELLED（CAS，重复取消返回友好提示）。
     */
    public String cancel(Long agentRunId) {
        Long userId = requireLogin();

        WorkflowActionLock.LockHandle lock = actionLock.acquireOrThrow(
                RedisConstants.AI_AGENT_RUN_ACTION_LOCK_KEY_PREFIX, agentRunId);
        try {
            AiAgentRun run = getOwnedRun(agentRunId, userId);
            if (!AiAgentRunStatus.WAITING_WORKFLOW_CONFIRM.name().equals(run.getStatus())) {
                throw new BusinessException(
                        BlogConstants.ErrorCode.CONFLICT,
                        "该建议已被处理或已过期"
                );
            }
            int updated = casStatus(agentRunId, AiAgentRunStatus.WAITING_WORKFLOW_CONFIRM, AiAgentRunStatus.CANCELLED);
            if (updated == 0) {
                throw new BusinessException(
                        BlogConstants.ErrorCode.CONFLICT,
                        "该建议已被处理"
                );
            }
            log.info("Agent 建议已取消: agentRunId={}", agentRunId);
            return "已取消建议，不会启动 Workflow。";
        } finally {
            actionLock.release(lock);
        }
    }

    private AiWorkflowRunVO confirmWithLock(Long agentRunId, Long userId) {
        AiAgentRun run = getOwnedRun(agentRunId, userId);
        log.info("Agent 建议确认读取 run: agentRunId={}, status={}", agentRunId, run.getStatus());

        if (!AiAgentRunStatus.WAITING_WORKFLOW_CONFIRM.name().equals(run.getStatus())) {
            throw new BusinessException(
                    BlogConstants.ErrorCode.CONFLICT,
                    "该建议已被处理或已过期"
            );
        }

        // 会话存在进行中 Workflow 时不允许再启动（不消耗状态，用户可先取消当前 Workflow）
        if (run.getSessionId() != null) {
            workflowRunManager.checkActiveWorkflowConflict(run.getSessionId(), userId);
            log.info("Agent 建议确认冲突检查通过: agentRunId={}, sessionId={}", agentRunId, run.getSessionId());
        }

        // 状态 CAS：一次性消费，防止两个不同 key 并发确认重复创建
        int updated = casStatus(agentRunId, AiAgentRunStatus.WAITING_WORKFLOW_CONFIRM, AiAgentRunStatus.CONFIRMING);
        if (updated == 0) {
            throw new BusinessException(
                    BlogConstants.ErrorCode.CONFLICT,
                    "该建议已被处理"
            );
        }
        log.info("Agent 建议确认 CAS 成功: agentRunId={}", agentRunId);

        AgentWorkflowSuggestion suggestion = readSuggestion(run);
        if (suggestion == null || !ALLOWED_WORKFLOW_TYPES.contains(suggestion.workflowType())) {
            throw new BusinessException(
                    BlogConstants.ErrorCode.SERVER_ERROR,
                    "建议数据异常，无法启动 Workflow"
            );
        }
        log.info("Agent 建议确认开始创建 Workflow: agentRunId={}, workflowType={}",
                agentRunId, suggestion.workflowType());

        // V3.12 写点 B：结论锚在**用户确认时刻**写，且必须在 createWorkflow 之前——
        // ArticleOptimizeWorkflowHandler.create 会读锚，此时读到的正是本次 suggestion.reason
        // （若放在之后，Handler 读到的是上一轮旧锚，本次确认的方向反而进不去）。
        // 未确认/已取消的建议结构性进不了锚：写点在 confirm 成功之后，不在 run 终态。
        // 事务：本方法由 @Transactional 的 confirm 调用，锚随确认事务一致提交/回滚——
        // Handler.create 抛异常则整体回滚（无残留）；runInitialSteps 内部失败则事务已提交（锚保留）。
        markConclusionIfOptimize(run, suggestion);

        AiWorkflowRunVO vo = createWorkflow(run, suggestion);
        markConfirmed(run, vo.getId());
        // 同步关联消息的 workflowRunId：刷新后按消息补拉 Workflow 卡（建议卡闭环）
        // 用非 lambda UpdateWrapper：纯 mock 单测环境无 MyBatis-Plus TableInfo 缓存，lambda wrapper 会炸
        aiMessageMapper.update(null, new UpdateWrapper<AiMessages>()
                .eq("agent_run_id", agentRunId)
                .set("workflow_run_id", vo.getId()));
        log.info("Agent 建议已确认并启动 Workflow: agentRunId={}, workflowType={}, workflowRunId={}",
                agentRunId, suggestion.workflowType(), vo.getId());
        return vo;
    }

    /**
     * V3.12 写点 B：确认优化建议时写结论锚。
     *
     * 仅 OPTIMIZE_ARTICLE 写（其他 Workflow 类型无「文章优化方向」语义）。
     * articleId 取 `suggestion.articleId()` 而**非** `run.getTargetArticleId()`——后者是
     * @TableField(exist=false) 瞬态字段，confirm 时从库里重新读出的 run 没有该值；
     * suggestion.articleId() 是 run 终态时 resolveSuggestionArticleId 的权威快照。
     *
     * 写失败不阻断确认（锚是增强不是依赖）：catch + log，用户仍能正常启动 Workflow。
     */
    private void markConclusionIfOptimize(AiAgentRun run, AgentWorkflowSuggestion suggestion) {
        try {
            if (!"OPTIMIZE_ARTICLE".equals(suggestion.workflowType())) {
                return;
            }
            Long articleId = parseArticleId(suggestion.articleId());
            anchorService.markConclusion(
                    run.getSessionId(),
                    articleId,
                    suggestion.reason(),
                    run.getId(),
                    ArticleSessionAnchorService.CONCLUSION_SOURCE_WORKFLOW_CONFIRMED
            );
        } catch (Exception e) {
            log.warn("确认时结论锚写入失败（不影响 Workflow 启动）: agentRunId={}", run.getId(), e);
        }
    }

    /**
     * 复用现有 Workflow 创建路径（不新建引擎）。
     * 计划定位与 chat 入口一致（单 ACTIVE 直接进、多 ACTIVE 候选让用户选）：
     * confirm 路径没有入口层定位，必须在这里补上，否则 Handler 会拒绝（planId/candidates 都为空）。
     */
    private AiWorkflowRunVO createWorkflow(AiAgentRun run, AgentWorkflowSuggestion suggestion) {
        return switch (suggestion.workflowType()) {
            case "LEARNING_PLAN" -> {
                AiWorkflowLearningPlanDTO dto = new AiWorkflowLearningPlanDTO();
                dto.setConversationId(run.getSessionId());
                dto.setGoal(suggestion.initialMessage());
                yield aiWorkflowRunService.createLearningPlanWorkflow(dto);
            }
            case "LEARNING_PROGRESS" -> {
                AiWorkflowLearningProgressDTO dto = new AiWorkflowLearningProgressDTO();
                dto.setConversationId(run.getSessionId());
                dto.setRequest(suggestion.initialMessage());
                applyPlanTarget(dto, run.getUserId(), suggestion.initialMessage());
                yield aiWorkflowRunService.createLearningProgressWorkflow(dto);
            }
            case "LEARNING_ASSIST" -> {
                AiWorkflowLearningAssistDTO dto = new AiWorkflowLearningAssistDTO();
                dto.setConversationId(run.getSessionId());
                dto.setRequest(suggestion.initialMessage());
                applyPlanTarget(dto, run.getUserId(), suggestion.initialMessage());
                // V4③ 第二刀第一阶段：确认时直接交接 reason（不改 ai_sessions 结论锚结构）。
                // 只有 planId 已被后端权威解析时才传，避免候选计划未确认时把方向串到后续用户选择。
                if (dto.getPlanId() != null && notBlank(suggestion.reason())) {
                    dto.setHandoffReason(suggestion.reason());
                }
                yield aiWorkflowRunService.createLearningAssistWorkflow(dto);
            }
            case "OPTIMIZE_ARTICLE" -> {
                // V2.5 文章侧建议：articleId 来自建议快照（后端权威线索），
                // 存在 + 归属校验由 ArticleOptimizeWorkflowHandler.create 双保险复核
                AiWorkflowOptimizeArticleDTO dto = new AiWorkflowOptimizeArticleDTO();
                dto.setConversationId(run.getSessionId());
                dto.setArticleId(parseArticleId(suggestion.articleId()));
                dto.setInstruction(suggestion.initialMessage());
                yield aiWorkflowRunService.createArticleOptimizeWorkflow(dto);
            }
            default -> throw new BusinessException(
                    BlogConstants.ErrorCode.SERVER_ERROR,
                    "不支持的 Workflow 类型：" + suggestion.workflowType()
            );
        };
    }

    /**
     * 建议快照中的 articleId 解析（V2.5）：null / 非数字视为脏数据，拒绝启动。
     * 存在 + 归属校验由 ArticleOptimizeWorkflowHandler 复核（双保险）。
     */
    private Long parseArticleId(String articleId) {
        if (articleId == null || !articleId.matches("\\d+")) {
            throw new BusinessException(
                    BlogConstants.ErrorCode.SERVER_ERROR,
                    "建议缺少文章 ID，无法启动优化流程"
            );
        }
        return Long.valueOf(articleId);
    }

    /**
     * 计划定位（与 chat 入口 routeLearningProgressWorkflow 一致）：
     * 唯一 ACTIVE → planId 直接进；多个 ACTIVE → candidates 让用户选；无 ACTIVE → 留给 Handler 拒绝。
     */
    private void applyPlanTarget(AiWorkflowLearningProgressDTO dto, Long userId, String message) {
        PlanTarget target = resolvePlanTarget(userId, message);
        if (target.planId() != null) {
            dto.setPlanId(target.planId());
        }
        if (!target.candidates().isEmpty()) {
            dto.setCandidates(target.candidates().stream()
                    .map(c -> new AiWorkflowLearningProgressDTO.Candidate(c.id(), c.title()))
                    .toList());
        }
    }

    private void applyPlanTarget(AiWorkflowLearningAssistDTO dto, Long userId, String message) {
        PlanTarget target = resolvePlanTarget(userId, message);
        if (target.planId() != null) {
            dto.setPlanId(target.planId());
        }
        if (!target.candidates().isEmpty()) {
            dto.setCandidates(target.candidates().stream()
                    .map(c -> new AiWorkflowLearningAssistDTO.Candidate(c.id(), c.title()))
                    .toList());
        }
    }

    private PlanTarget resolvePlanTarget(Long userId, String message) {
        // 修复：消息点名（建议 initialMessage = 用户原句）唯一命中 → 直进，与 chat 入口一致——
        // 原来只按 userId 判断（多 ACTIVE 全塞候选），用户原句点名完全被无视，多计划用户必被要求再选一遍
        if (message != null && !message.isBlank()) {
            List<LearningPlans> mentioned = learningPlansService.matchPlansByMessage(userId, message);
            if (mentioned.size() == 1) {
                return new PlanTarget(mentioned.get(0).getId(), List.of());
            }
        }
        List<LearningPlans> actives = learningPlansService.listByUser(userId).stream()
                .filter(plan -> LearningPlans.STATUS_ACTIVE.equals(plan.getStatus()))
                .toList();
        if (actives.size() == 1) {
            return new PlanTarget(actives.get(0).getId(), List.of());
        }
        if (actives.size() > 1) {
            return new PlanTarget(null, actives.stream()
                    .map(plan -> new PlanRef(plan.getId(), plan.getTitle()))
                    .toList());
        }
        return new PlanTarget(null, List.of());
    }

    private record PlanTarget(Long planId, List<PlanRef> candidates) {
    }

    private record PlanRef(Long id, String title) {
    }

    private void markConfirmed(AiAgentRun run, String workflowRunId) {
        AiAgentRun patch = new AiAgentRun();
        patch.setId(run.getId());
        patch.setStatus(AiAgentRunStatus.COMPLETED.name());
        patch.setContextJson(appendWorkflowRunId(run, workflowRunId));
        patch.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(patch);
    }

    /**
     * context_json.pendingWorkflowSuggestion.workflowRunId = 新 Workflow run id，
     * 便于审计与重复 confirm 时找回同一 Workflow。
     */
    private String appendWorkflowRunId(AiAgentRun run, String workflowRunId) {
        try {
            JsonNode root = objectMapper.readTree(run.getContextJson());
            Map<String, Object> context = new HashMap<>();
            root.properties().forEach(entry -> context.put(entry.getKey(), readValue(entry.getValue())));
            Object suggestion = context.get("pendingWorkflowSuggestion");
            Map<String, Object> target;
            if (suggestion instanceof Map<?, ?> raw) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) raw;
                target = map;
            } else {
                target = new HashMap<>();
                context.put("pendingWorkflowSuggestion", target);
            }
            target.put("workflowRunId", workflowRunId);
            return toJson(context);
        } catch (Exception e) {
            log.warn("Agent 建议 context 更新失败，仅落 workflowRunId: agentRunId={}", run.getId(), e);
            return toJson(Map.of("pendingWorkflowSuggestion", Map.of("workflowRunId", workflowRunId)));
        }
    }

    private Object readValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject() || node.isArray()) {
            try {
                return objectMapper.convertValue(node, Map.class);
            } catch (Exception e) {
                return node.asText();
            }
        }
        return node.asText();
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

    private int casStatus(Long agentRunId, AiAgentRunStatus expect, AiAgentRunStatus target) {
        AiAgentRun patch = new AiAgentRun();
        patch.setStatus(target.name());
        patch.setUpdatedAt(LocalDateTime.now());
        return runMapper.update(patch, new LambdaQueryWrapper<AiAgentRun>()
                .eq(AiAgentRun::getId, agentRunId)
                .eq(AiAgentRun::getStatus, expect.name()));
    }

    private AgentWorkflowSuggestion readSuggestion(AiAgentRun run) {
        try {
            JsonNode root = objectMapper.readTree(run.getContextJson());
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
            log.warn("Agent 建议 context 解析失败: agentRunId={}", run.getId(), e);
            return null;
        }
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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
