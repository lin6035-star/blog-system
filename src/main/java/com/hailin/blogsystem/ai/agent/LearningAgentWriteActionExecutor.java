package com.hailin.blogsystem.ai.agent;

import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.LearningStages;
import com.hailin.blogsystem.entity.vo.LearningPlansDetailVO;
import com.hailin.blogsystem.exception.BusinessException;
import com.hailin.blogsystem.mapper.LearningStageMapper;
import com.hailin.blogsystem.service.LearningPlansService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 学习域写动作执行器（V3.6，V3.5 登记 A 刀兑现）。
 *
 * 从 AgentWriteActionService 迁入学习域 3 动作（V2.4 勾选 / V3.1 追加 / V3.3 重命名）
 * 及其定位器（resolvePlanAndStage / resolveTaskIndex / readStageTasks），域内私有化。
 * 安全语义零变化：标题匹配裁判（不信任 LLM 索引）、重复预检、@Version CAS 由 service 兜底。
 */
@Component
@Slf4j
public class LearningAgentWriteActionExecutor implements AgentWriteActionExecutor {

    private final LearningStageMapper learningStageMapper;
    private final LearningPlansService learningPlansService;

    public LearningAgentWriteActionExecutor(
            LearningStageMapper learningStageMapper,
            LearningPlansService learningPlansService
    ) {
        this.learningStageMapper = learningStageMapper;
        this.learningPlansService = learningPlansService;
    }

    @Override
    public boolean supports(String actionType) {
        return AgentWriteProposal.TYPE_UPDATE_TASK_DONE.equals(actionType)
                || AgentWriteProposal.TYPE_ADD_LEARNING_TASK.equals(actionType)
                || AgentWriteProposal.TYPE_UPDATE_LEARNING_TASK.equals(actionType);
    }

    @Override
    public WriteActionResult execute(AiAgentRun run, AgentWriteProposal proposal, Long userId) {
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
    private WriteActionResult executeUpdateTaskDone(AiAgentRun run, AgentWriteProposal proposal, Long userId) {
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

        Map<String, Object> executed = new HashMap<>();
        executed.put("planId", target.plan().getId());
        executed.put("stageId", target.stage().getId());
        executed.put("taskIndex", taskIndex);
        executed.put("stageTitle", proposal.stageTitle());
        executed.put("taskTitle", proposal.taskTitle());
        executed.put("done", proposal.done());
        executed.put("beforeTasks", beforeTasks);
        executed.put("afterTasks", afterTasks);

        log.info("写动作执行完成: runId={}, {}", run.getId(), resultMessage);
        return new WriteActionResult(resultMessage, executed);
    }

    /**
     * ADD_LEARNING_TASK（V3.1）：定位 计划→阶段（taskTitle 是新任务，不做存在校验）→
     * 重复预检 → appendTasks + 快照留痕。不批量、不重命名、不删除。
     */
    private WriteActionResult executeAddLearningTask(AiAgentRun run, AgentWriteProposal proposal, Long userId) {
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

        Map<String, Object> executed = new HashMap<>();
        executed.put("planId", target.plan().getId());
        executed.put("stageId", target.stage().getId());
        executed.put("stageTitle", proposal.stageTitle());
        executed.put("taskTitle", proposal.taskTitle());
        executed.put("done", proposal.done());
        executed.put("beforeTasks", beforeTasks);
        executed.put("afterTasks", afterTasks);

        log.info("写动作执行完成: runId={}, {}", run.getId(), resultMessage);
        return new WriteActionResult(resultMessage, executed);
    }

    /**
     * UPDATE_LEARNING_TASK（V3.3）：定位 计划→阶段→旧任务（taskTitle 必须已存在且唯一）→
     * 新名重复预检（排除 taskIndex 自身）→ renameTask + 快照留痕。同名改名、复合动作不做。
     */
    private WriteActionResult executeRenameLearningTask(AiAgentRun run, AgentWriteProposal proposal, Long userId) {
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

        Map<String, Object> executed = new HashMap<>();
        executed.put("planId", target.plan().getId());
        executed.put("stageId", target.stage().getId());
        executed.put("taskIndex", taskIndex);
        executed.put("stageTitle", proposal.stageTitle());
        executed.put("taskTitle", proposal.taskTitle());
        executed.put("done", proposal.done());
        if (proposal.newTitle() != null) {
            executed.put("newTitle", proposal.newTitle());
        }
        executed.put("beforeTasks", beforeTasks);
        executed.put("afterTasks", afterTasks);

        log.info("写动作执行完成: runId={}, {}", run.getId(), resultMessage);
        return new WriteActionResult(resultMessage, executed);
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
            List<LearningPlans> matched = learningPlansService.matchPlansByMessage(userId, proposal.planRef());
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
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST,
                    "提案缺少阶段标题");
        }
        LearningPlansDetailVO detail = learningPlansService.getDetail(plan.getId(), userId);
        if (detail == null || detail.getStages() == null) {
            throw new BusinessException(BlogConstants.ErrorCode.NOT_FOUND,
                    "计划详情读取失败");
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

    /** 计划 + 阶段定位结果（含 stage 任务列表，供后续任务定位 / 重复预检复用） */
    private record PlanStageTarget(LearningPlans plan, LearningPlansDetailVO.StageProgress stage) {
    }
}
