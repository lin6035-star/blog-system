package com.hailin.blogsystem.ai.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.workflow.WorkflowActionLock;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import com.hailin.blogsystem.exception.BusinessException;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 受控写动作壳（V2.4 / V3.6）：确认 / 取消 / 快照。
 *
 * 安全模型（"LLM 给建议，后端做裁判"的写动作延伸）：
 * - Agent 只提案（标题类信息），索引/定位一律不用 LLM 给的，由域执行器确定性匹配
 * - 用户确认（WAITING_WRITE_CONFIRM → CONFIRMING → COMPLETED）后才执行，
 *   Redis 锁 + 状态 CAS 防并发双确认
 * - 执行留 before/after 快照（改前/改后），可解释可回滚
 * - 幂等：靠状态 CAS 一次性消费（同 key 重试拿到"已被处理"，不重复执行写动作）
 *
 * V3.6 壳化（V3.5 登记 A 刀）：域执行逻辑迁出到 AgentWriteActionExecutor 实现
 * （LearningAgentWriteActionExecutor 3 动作 / ArticleAgentWriteActionExecutor 1 动作），
 * 本类只保留公共确认骨架：锁 / 状态 CAS / run 归属 / 提案解析 / 统一留痕 / 按 actionType 分发。
 * 新写动作 = 域执行器加 supports/分支 + 域 service 调用，不再串改本类。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentWriteActionService {

    private final AiAgentRunMapper runMapper;
    private final ObjectMapper objectMapper;
    private final WorkflowActionLock actionLock;
    private final List<AgentWriteActionExecutor> executors;

    /**
     * 确认写动作提案：按 actionType 分发到域执行器 → 快照留痕。
     */
    @Transactional
    public String confirmWrite(Long agentRunId) {
        Long userId = requireLogin();
        log.info("写动作确认请求进入: agentRunId={}, userId={}", agentRunId, userId);

        WorkflowActionLock.LockHandle lock = actionLock.acquireOrThrow(
                RedisConstants.AI_AGENT_RUN_ACTION_LOCK_KEY_PREFIX, agentRunId);
        try {
            return confirmWithLock(agentRunId, userId);
        } finally {
            actionLock.release(lock);
            log.info("写动作确认锁已释放: agentRunId={}", agentRunId);
        }
    }

    /**
     * 取消写动作提案：WAITING_WRITE_CONFIRM -> CANCELLED（不执行任何写操作）。
     */
    public String cancelWrite(Long agentRunId) {
        Long userId = requireLogin();

        WorkflowActionLock.LockHandle lock = actionLock.acquireOrThrow(
                RedisConstants.AI_AGENT_RUN_ACTION_LOCK_KEY_PREFIX, agentRunId);
        try {
            AiAgentRun run = getOwnedRun(agentRunId, userId);
            if (!AiAgentRunStatus.WAITING_WRITE_CONFIRM.name().equals(run.getStatus())) {
                throw new BusinessException(
                        BlogConstants.ErrorCode.CONFLICT,
                        "该提案已被处理或已过期"
                );
            }
            int updated = casStatus(agentRunId, AiAgentRunStatus.WAITING_WRITE_CONFIRM, AiAgentRunStatus.CANCELLED);
            if (updated == 0) {
                throw new BusinessException(
                        BlogConstants.ErrorCode.CONFLICT,
                        "该提案已被处理"
                );
            }
            log.info("写动作提案已取消: agentRunId={}", agentRunId);
            return "已取消提案，不会执行任何修改。";
        } finally {
            actionLock.release(lock);
        }
    }

    /**
     * 写动作提案快照（前端历史消息恢复写动作卡）。
     */
    public AgentWriteActionView getWriteAction(Long agentRunId) {
        Long userId = requireLogin();
        AiAgentRun run = getOwnedRun(agentRunId, userId);
        if (run.getStatus() == null
                || !AiAgentRunStatus.WAITING_WRITE_CONFIRM.name().equals(run.getStatus())) {
            return new AgentWriteActionView(run.getStatus(), null);
        }
        return new AgentWriteActionView(run.getStatus(), parseWriteAction(run.getContextJson()));
    }

    private String confirmWithLock(Long agentRunId, Long userId) {
        AiAgentRun run = getOwnedRun(agentRunId, userId);
        if (!AiAgentRunStatus.WAITING_WRITE_CONFIRM.name().equals(run.getStatus())) {
            throw new BusinessException(
                    BlogConstants.ErrorCode.CONFLICT,
                    "该提案已被处理或已过期"
            );
        }

        // 状态 CAS：一次性消费（防双击/异 key 并发重复执行写动作）
        int updated = casStatus(agentRunId, AiAgentRunStatus.WAITING_WRITE_CONFIRM, AiAgentRunStatus.CONFIRMING);
        if (updated == 0) {
            throw new BusinessException(
                    BlogConstants.ErrorCode.CONFLICT,
                    "该提案已被处理"
            );
        }
        log.info("写动作确认 CAS 成功: agentRunId={}", agentRunId);

        AgentWriteProposal proposal = parseWriteAction(run.getContextJson());
        if (proposal == null || proposal.actionType() == null || proposal.actionType().isBlank()) {
            throw new BusinessException(
                    BlogConstants.ErrorCode.SERVER_ERROR,
                    "提案数据异常，无法执行"
            );
        }

        // V3.6 分发：按内层写动作类型找域执行器（注册表式）；未注册 = 数据异常（防新类型静默吞）
        AgentWriteActionExecutor executor = executors.stream()
                .filter(e -> e.supports(proposal.actionType()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        BlogConstants.ErrorCode.SERVER_ERROR,
                        "提案数据异常，无法执行"));
        AgentWriteActionExecutor.WriteActionResult result = executor.execute(run, proposal, userId);

        markExecuted(run, proposal, result.auditPayload());
        log.info("写动作执行完成: runId={}, {}", run.getId(), result.message());
        return result.message();
    }

    /**
     * 统一留痕：run 置 COMPLETED + context_json 追加 writeActionResult。
     * actionType / executedAt 由壳统一补，域字段 + before/after 快照由执行器载荷提供。
     */
    private void markExecuted(AiAgentRun run, AgentWriteProposal proposal, Map<String, Object> executed) {
        try {
            Map<String, Object> context = parseContext(run.getContextJson());
            executed.put("actionType", proposal.actionType());
            executed.put("executedAt", LocalDateTime.now().toString());
            context.put("writeActionResult", executed);

            AiAgentRun patch = new AiAgentRun();
            patch.setId(run.getId());
            patch.setStatus(AiAgentRunStatus.COMPLETED.name());
            patch.setContextJson(toJson(context));
            patch.setUpdatedAt(LocalDateTime.now());
            runMapper.updateById(patch);
        } catch (Exception e) {
            log.warn("写动作结果留痕失败: agentRunId={}", run.getId(), e);
        }
    }

    private AgentWriteProposal parseWriteAction(String contextJson) {
        if (contextJson == null || contextJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(contextJson);
            JsonNode node = root.path("pendingWriteAction");
            if (node.isMissingNode() || !node.isObject()) {
                return null;
            }
            return new AgentWriteProposal(
                    node.path("actionType").asText(null),
                    node.path("planRef").asText(null),
                    node.path("stageTitle").asText(null),
                    node.path("taskTitle").asText(null),
                    Boolean.parseBoolean(node.path("done").asText("false")),
                    node.path("newTitle").asText(null),
                    node.path("articleId").asText(null),
                    node.path("articleTitle").asText(null)
            );
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> parseContext(String contextJson) {
        try {
            return objectMapper.readValue(contextJson, Map.class);
        } catch (Exception e) {
            return new HashMap<>();
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

    private int casStatus(Long agentRunId, AiAgentRunStatus expect, AiAgentRunStatus target) {
        AiAgentRun patch = new AiAgentRun();
        patch.setStatus(target.name());
        patch.setUpdatedAt(LocalDateTime.now());
        return runMapper.update(patch, new LambdaQueryWrapper<AiAgentRun>()
                .eq(AiAgentRun::getId, agentRunId)
                .eq(AiAgentRun::getStatus, expect.name()));
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
}
