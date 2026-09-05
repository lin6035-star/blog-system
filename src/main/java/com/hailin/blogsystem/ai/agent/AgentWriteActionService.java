package com.hailin.blogsystem.ai.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.workflow.WorkflowActionLock;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.LearningStages;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import com.hailin.blogsystem.entity.vo.LearningPlansDetailVO;
import com.hailin.blogsystem.exception.BusinessException;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.mapper.LearningStageMapper;
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

/**
 * Agent 受控写动作（V2.4）：确认 / 取消 / 快照。
 *
 * 安全模型（"LLM 给建议，后端做裁判"的写动作延伸）：
 * - Agent 只提案（planRef/stageTitle/taskTitle/done），索引一律不用 LLM 给的
 * - 后端确定性匹配：计划（点名/单 ACTIVE）→ 阶段标题 → 任务标题；
 *   匹配不到/重名 → 拒绝或列候选，绝不猜
 * - 用户确认（WAITING_WRITE_CONFIRM → CONFIRMING → COMPLETED）后才执行，
 *   Redis 锁 + 状态 CAS 防并发双确认
 * - 执行留 before/after 快照（改前/改后 tasks JSON + 解析结果），可解释可回滚
 * - 幂等：靠状态 CAS 一次性消费（同 key 重试拿到"已被处理"，不重复执行写动作）
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentWriteActionService {

    private final AiAgentRunMapper runMapper;
    private final LearningStageMapper learningStageMapper;
    private final ObjectMapper objectMapper;
    private final WorkflowActionLock actionLock;
    private final LearningPlansService learningPlansService;

    /**
     * 确认写动作提案：标题匹配 → 快照 → 执行 updateTaskDone → 结果留痕。
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

        // 按写动作类型分支（双层 actionType：外层 SUGGEST_WRITE 已消费，此处是内层写动作类型）
        return switch (proposal.actionType()) {
            case AgentWriteProposal.TYPE_UPDATE_TASK_DONE -> executeUpdateTaskDone(run, proposal, userId);
            case AgentWriteProposal.TYPE_ADD_LEARNING_TASK -> executeAddLearningTask(run, proposal, userId);
            case AgentWriteProposal.TYPE_UPDATE_LEARNING_TASK -> executeRenameLearningTask(run, proposal, userId);
            default -> throw new BusinessException(
                    BlogConstants.ErrorCode.SERVER_ERROR,
                    "提案数据异常，无法执行");
        };
    }

    /**
     * UPDATE_TASK_DONE（V2.4）：定位 计划→阶段→任务（任务必须已存在且唯一）→ updateTaskDone + 快照留痕。
     */
    private String executeUpdateTaskDone(AiAgentRun run, AgentWriteProposal proposal, Long userId) {
        PlanStageTarget target = resolvePlanAndStage(proposal, userId);
        int taskIndex = resolveTaskIndex(target.stage(), proposal);
        log.info("写动作目标解析成功: runId={}, planId={}, stageId={}, taskIndex={}",
                run.getId(), target.plan().getId(), target.stage().getId(), taskIndex);

        // before 快照
        String beforeTasks = readStageTasks(target.stage().getId());
        learningPlansService.updateTaskDone(
                target.plan().getId(), target.stage().getId(), taskIndex, proposal.done(), userId);
        // after 快照
        String afterTasks = readStageTasks(target.stage().getId());

        String resultMessage = proposal.done()
                ? "已勾选任务「" + proposal.taskTitle() + "」为完成。"
                : "已取消任务「" + proposal.taskTitle() + "」的完成状态。";

        markExecuted(run, proposal, target.plan().getId(), target.stage().getId(), taskIndex,
                beforeTasks, afterTasks);
        log.info("写动作执行完成: runId={}, {}", run.getId(), resultMessage);
        return resultMessage;
    }

    /**
     * ADD_LEARNING_TASK（V3.1）：定位 计划→阶段（taskTitle 是新任务，不做存在校验）→
     * 重复预检 → appendTasks + 快照留痕。不批量、不重命名、不删除。
     */
    private String executeAddLearningTask(AiAgentRun run, AgentWriteProposal proposal, Long userId) {
        if (proposal.taskTitle() == null || proposal.taskTitle().isBlank()
                || proposal.stageTitle() == null || proposal.stageTitle().isBlank()) {
            throw new BusinessException(
                    BlogConstants.ErrorCode.BAD_REQUEST,
                    "提案缺少任务标题或阶段标题");
        }
        PlanStageTarget target = resolvePlanAndStage(proposal, userId);
        log.info("写动作目标解析成功: runId={}, planId={}, stageId={}",
                run.getId(), target.plan().getId(), target.stage().getId());

        // 重复预检：同名任务已存在 → 明确拒绝，不依赖 appendTasks 的静默去重 return（void 无感知）
        if (target.stage().getTasks() != null) {
            boolean duplicate = target.stage().getTasks().stream()
                    .anyMatch(t -> proposal.taskTitle().trim().equalsIgnoreCase(
                            t.getTitle() == null ? "" : t.getTitle().trim()));
            if (duplicate) {
                throw new BusinessException(
                        BlogConstants.ErrorCode.CONFLICT,
                        "任务「" + proposal.taskTitle() + "」已在阶段「" + proposal.stageTitle() + "」中");
            }
        }

        String beforeTasks = readStageTasks(target.stage().getId());
        learningPlansService.appendTasks(target.plan().getId(), target.stage().getId(),
                List.of(proposal.taskTitle()), userId);
        String afterTasks = readStageTasks(target.stage().getId());

        String resultMessage = "已向 计划「" + target.plan().getTitle() + "」· 阶段「"
                + proposal.stageTitle() + "」追加任务「" + proposal.taskTitle() + "」。";

        markExecuted(run, proposal, target.plan().getId(), target.stage().getId(), null,
                beforeTasks, afterTasks);
        log.info("写动作执行完成: runId={}, {}", run.getId(), resultMessage);
        return resultMessage;
    }

    /**
     * UPDATE_LEARNING_TASK（V3.3）：定位 计划→阶段→旧任务（taskTitle 必须已存在且唯一）→
     * 新名重复预检（排除 taskIndex 自身）→ renameTask + 快照留痕。同名改名、复合动作不做。
     */
    private String executeRenameLearningTask(AiAgentRun run, AgentWriteProposal proposal, Long userId) {
        if (proposal.taskTitle() == null || proposal.taskTitle().isBlank()
                || proposal.newTitle() == null || proposal.newTitle().isBlank()
                || proposal.stageTitle() == null || proposal.stageTitle().isBlank()) {
            throw new BusinessException(
                    BlogConstants.ErrorCode.BAD_REQUEST,
                    "提案缺少任务标题、新任务名或阶段标题");
        }
        // 同名改名边界（防御纵深：提案前已预检过，此处再拒——提案可能被篡改/旧版本生成）
        if (proposal.newTitle().trim().equalsIgnoreCase(proposal.taskTitle().trim())) {
            throw new BusinessException(
                    BlogConstants.ErrorCode.BAD_REQUEST,
                    "新任务名与原任务名相同");
        }
        PlanStageTarget target = resolvePlanAndStage(proposal, userId);
        int taskIndex = resolveTaskIndex(target.stage(), proposal);
        log.info("写动作目标解析成功: runId={}, planId={}, stageId={}, taskIndex={}",
                run.getId(), target.plan().getId(), target.stage().getId(), taskIndex);

        // 新名重复预检（排除 taskIndex 自身，防御纵深，镜像 ADD 分支）
        if (target.stage().getTasks() != null) {
            for (int i = 0; i < target.stage().getTasks().size(); i++) {
                if (i == taskIndex) {
                    continue;
                }
                String title = target.stage().getTasks().get(i).getTitle();
                if (proposal.newTitle().trim().equalsIgnoreCase(title == null ? "" : title.trim())) {
                    throw new BusinessException(
                            BlogConstants.ErrorCode.CONFLICT,
                            "任务「" + proposal.newTitle() + "」已在阶段「" + proposal.stageTitle() + "」中");
                }
            }
        }

        // before 快照
        String beforeTasks = readStageTasks(target.stage().getId());
        learningPlansService.renameTask(target.plan().getId(), target.stage().getId(),
                taskIndex, proposal.newTitle().trim(), userId);
        // after 快照
        String afterTasks = readStageTasks(target.stage().getId());

        String resultMessage = "已将任务「" + proposal.taskTitle() + "」重命名为「"
                + proposal.newTitle().trim() + "」。";

        markExecuted(run, proposal, target.plan().getId(), target.stage().getId(), taskIndex,
                beforeTasks, afterTasks);
        log.info("写动作执行完成: runId={}, {}", run.getId(), resultMessage);
        return resultMessage;
    }

    /**
     * 后端确定性匹配（不信任 LLM 索引）第一步：计划 → 阶段。
     * planRef 点名唯一 → 用；未点名单 ACTIVE → 用；多 ACTIVE → 拒绝（让用户说清楚）
     * stageTitle 唯一 → 用；重名 → 拒绝；无 → 拒绝
     * 返回 plan + stage（detail VO 含任务列表，供 UPDATE 的任务定位 / ADD 的重复预检复用，避免二次查询）
     */
    private PlanStageTarget resolvePlanAndStage(AgentWriteProposal proposal, Long userId) {
        // 1. 计划
        List<LearningPlans> plans = learningPlansService.listByUser(userId);
        List<LearningPlans> activePlans = plans.stream()
                .filter(plan -> LearningPlans.STATUS_ACTIVE.equals(plan.getStatus()))
                .toList();
        LearningPlans plan;
        if (proposal.planRef() != null && !proposal.planRef().isBlank()) {
            List<LearningPlans> matched = learningPlansService.matchActivePlansByMessage(userId, proposal.planRef());
            if (matched.size() != 1) {
                throw new BusinessException(BlogConstants.ErrorCode.CONFLICT,
                        matched.isEmpty()
                                ? "没有找到与「" + proposal.planRef() + "」匹配的进行中计划"
                                : "匹配到多个计划，请说明具体是哪个计划");
            }
            plan = matched.get(0);
        } else if (activePlans.size() == 1) {
            plan = activePlans.get(0);
        } else {
            throw new BusinessException(BlogConstants.ErrorCode.CONFLICT,
                    activePlans.isEmpty() ? "当前没有进行中的学习计划" : "有多个进行中的计划，请说明是哪个计划");
        }

        // 2. 阶段标题
        if (proposal.stageTitle() == null || proposal.stageTitle().isBlank()) {
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST, "提案缺少阶段标题");
        }
        LearningPlansDetailVO detail = learningPlansService.getDetail(plan.getId(), userId);
        if (detail == null || detail.getStages() == null) {
            throw new BusinessException(BlogConstants.ErrorCode.NOT_FOUND, "计划详情读取失败");
        }
        List<LearningPlansDetailVO.StageProgress> stageMatches = detail.getStages().stream()
                .filter(stage -> proposal.stageTitle().trim().equalsIgnoreCase(
                        stage.getTitle() == null ? "" : stage.getTitle().trim()))
                .toList();
        if (stageMatches.size() != 1) {
            throw new BusinessException(BlogConstants.ErrorCode.CONFLICT,
                    stageMatches.isEmpty()
                            ? "没有找到阶段「" + proposal.stageTitle() + "」"
                            : "阶段「" + proposal.stageTitle() + "」有多个同名项，请说明具体位置");
        }
        return new PlanStageTarget(plan, stageMatches.get(0));
    }

    /**
     * UPDATE_TASK_DONE 专属第三步：任务标题（目标阶段内）必须已存在且唯一 → taskIndex。
     * ADD_LEARNING_TASK 不走这里——它的 taskTitle 是新任务，本来就该不存在（Codex 评审修正）。
     */
    private int resolveTaskIndex(LearningPlansDetailVO.StageProgress stage, AgentWriteProposal proposal) {
        if (stage.getTasks() == null || stage.getTasks().isEmpty()) {
            throw new BusinessException(BlogConstants.ErrorCode.NOT_FOUND,
                    "阶段「" + proposal.stageTitle() + "」暂无任务");
        }
        int taskIndex = -1;
        int matchCount = 0;
        for (int i = 0; i < stage.getTasks().size(); i++) {
            String title = stage.getTasks().get(i).getTitle();
            if (proposal.taskTitle().trim().equalsIgnoreCase(title == null ? "" : title.trim())) {
                taskIndex = i;
                matchCount++;
            }
        }
        if (matchCount != 1) {
            throw new BusinessException(BlogConstants.ErrorCode.CONFLICT,
                    matchCount == 0
                            ? "没有找到任务「" + proposal.taskTitle() + "」"
                            : "任务「" + proposal.taskTitle() + "」有多个同名项，请说明具体是哪个");
        }
        return taskIndex;
    }

    /** 读阶段 tasks 原始 JSON（before/after 快照用） */
    private String readStageTasks(Long stageId) {
        LearningStages stage = learningStageMapper.selectById(stageId);
        return stage == null ? null : stage.getTasks();
    }

    private void markExecuted(AiAgentRun run, AgentWriteProposal proposal, Long planId, Long stageId,
                              Integer taskIndex, String beforeTasks, String afterTasks) {
        try {
            Map<String, Object> context = parseContext(run.getContextJson());
            Map<String, Object> executed = new HashMap<>();
            executed.put("actionType", proposal.actionType());
            executed.put("planId", planId);
            executed.put("stageId", stageId);
            if (taskIndex != null) {
                executed.put("taskIndex", taskIndex);
            }
            executed.put("stageTitle", proposal.stageTitle());
            executed.put("taskTitle", proposal.taskTitle());
            executed.put("done", proposal.done());
            if (proposal.newTitle() != null) {
                executed.put("newTitle", proposal.newTitle());
            }
            executed.put("beforeTasks", beforeTasks);
            executed.put("afterTasks", afterTasks);
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
                    node.path("newTitle").asText(null)
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

    /** 计划 + 阶段定位结果（含 stage 任务列表，供后续任务定位 / 重复预检复用） */
    private record PlanStageTarget(LearningPlans plan, LearningPlansDetailVO.StageProgress stage) {
    }
}
