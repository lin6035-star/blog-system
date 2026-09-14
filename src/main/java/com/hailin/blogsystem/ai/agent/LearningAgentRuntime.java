package com.hailin.blogsystem.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.entity.vo.LearningPlansDetailVO;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.mapper.AiAgentStepMapper;
import com.hailin.blogsystem.service.LearningPlansService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 学习域 Agent Runtime（V1 只读 Loop + V2.1 建议桥 + V2.4 受控写）。
 *
 * V2.5 起继承 AbstractAgentRuntime 公共循环骨架，本类只保留学习域差异：
 * - 白名单：QUERY_LEARNING_DASHBOARD / QUERY_MEMORY / SEARCH_RAG + 通用终态 + SUGGEST_WRITE
 * - 建议白名单：仅学习类 Workflow（LEARNING_PLAN / LEARNING_PROGRESS / LEARNING_ASSIST）
 * - SUGGEST_WRITE 扩展终态：写动作提案（确认门控，执行在 AgentWriteActionService）
 *
 * 循环/终态/落库/过期清理/零观察裁判等通用逻辑在基类，行为与 V1 完全一致。
 */
@Service
@Slf4j
public class LearningAgentRuntime extends AbstractAgentRuntime implements AgentRuntime {

    private static final Set<AgentStepActionType> ALLOWED_ACTIONS = Set.of(
            AgentStepActionType.QUERY_LEARNING_DASHBOARD,
            AgentStepActionType.QUERY_MEMORY,
            AgentStepActionType.SEARCH_RAG,
            AgentStepActionType.ASK_USER,
            AgentStepActionType.FINAL_ANSWER,
            AgentStepActionType.SUGGEST_WORKFLOW,
            AgentStepActionType.SUGGEST_WRITE
    );

    /**
     * V2.1：Agent 只能建议学习类 Workflow（写动作 / 文章类 Workflow 不允许）。
     * confirm 时还会二次校验。
     */
    private static final Set<String> ALLOWED_SUGGEST_WORKFLOW_TYPES = Set.of(
            "LEARNING_PLAN", "LEARNING_PROGRESS", "LEARNING_ASSIST"
    );

    private final AgentStepDecider decider;
    private final LearningPlansService learningPlansService;
    private final LearningPlanAnchorService learningPlanAnchorService;

    public LearningAgentRuntime(
            @org.springframework.beans.factory.annotation.Qualifier("llmAgentStepDecider")
            AgentStepDecider decider,
            LearningAgentActionExecutor executor,
            AiAgentRunMapper runMapper,
            AiAgentStepMapper stepMapper,
            ObjectMapper objectMapper,
            LearningPlansService learningPlansService,
            LearningPlanAnchorService learningPlanAnchorService
    ) {
        super(executor, runMapper, stepMapper, objectMapper);
        this.decider = decider;
        this.learningPlansService = learningPlansService;
        this.learningPlanAnchorService = learningPlanAnchorService;
    }

    /**
     * 注入会话学习计划锚（V4.x 补口）。
     *
     * **解决什么**：分类器只吃当前这一条消息，用户第二轮省略主语时（"帮我看看我的计划怎么样"），
     * 决策器手里没有任何"刚才聊的是哪个计划"的依据 → 只能列一串候选反问"是哪个"，
     * 哪怕上一轮刚聊过。锚本来是给工作流路径用的（`LearningPlanAnchorService.resolve`
     * 的消费点原先只有两个 route 方法的兜底链），**Agent Runtime 路径不读**。
     *
     * 与文章域 `ArticleAgentRuntime.effectiveGoal` 同构：锚只作**定位线索**注入 goal，
     * 不替决策器做选择——决策器把它填进 `input.planRef`，执行器仍按字符串匹配定位
     * （用户原话点名了别的计划时以原话为准，不会被这条线索带偏）。
     */
    @Override
    protected String effectiveGoal(String goal, Long userId, Long sessionId, PageContextDTO pageContext) {
        StringBuilder sb = new StringBuilder(goal);

        LearningPlans anchor = learningPlanAnchorService.resolve(sessionId, userId);
        if (anchor != null) {
            sb.append("\n【会话最近讨论的学习计划】《").append(anchor.getTitle())
                    .append("》(ID ").append(anchor.getId()).append(")");
        } else {
            sb.append("\n【会话最近讨论的学习计划】无");
        }

        sb.append("\n（以上只是定位线索：用户省略主语、说\"这个计划 / 它\"时很可能就是指它——"
                + "需要读计划内容时，在 QUERY_LEARNING_DASHBOARD 的 input.planRef 填该计划标题；"
                + "用户原话明确点了别的计划时以原话为准，不要被这条线索带偏）");
        return sb.toString();
    }

    // ==================== 领域钩子 ====================

    @Override
    protected AgentStepDecider decider() {
        return decider;
    }

    /** SUGGEST_WRITE 是扩展终态：领域拒绝（零观察 / 重复预检）后循环跳过普通执行 */
    @Override
    protected boolean isTerminalExtension(AgentStepDecision decision) {
        return decision != null && decision.actionType() == AgentStepActionType.SUGGEST_WRITE;
    }

    @Override
    protected Set<AgentStepActionType> allowedActions() {
        return ALLOWED_ACTIONS;
    }

    @Override
    protected Set<String> allowedSuggestWorkflowTypes() {
        return ALLOWED_SUGGEST_WORKFLOW_TYPES;
    }

    @Override
    protected String emptyAnswerFallback() {
        return "已为你整理学习建议，但未生成具体内容。";
    }

    @Override
    protected String emptyAskUserFallback() {
        return "请补充一下你的学习目标？";
    }

    @Override
    protected String defaultWorkflowSuggestionReason(String workflowType) {
        return "根据当前学习情况，建议进入「" + workflowType + "」流程。";
    }

    @Override
    protected String suggestWorkflowRejectHint() {
        return "系统提示：你在没有任何查询结果时尝试建议启动 Workflow，后端已拒绝。"
                + "请先执行只读查询（QUERY_LEARNING_DASHBOARD / QUERY_MEMORY / SEARCH_RAG）再决策。";
    }

    @Override
    protected String emptySummaryFallback() {
        return "暂时没有查到你的学习进度信息，可以先去创建一个学习计划。";
    }

    @Override
    protected String summaryHeader() {
        return "根据当前学习情况，整理如下：\n";
    }

    /**
     * 学习域扩展终态：SUGGEST_WRITE（V2.4 受控写动作提案）。
     * 裁判规则与 SUGGEST_WORKFLOW 一致：至少 1 条观察才允许提案（没查就提案 = 拍脑袋）。
     */
    @Override
    protected AgentRunResult handleExtraTerminalAction(
            AiAgentRun run,
            AgentStepDecision decision,
            String goal,
            List<String> observations,
            AgentStepEmitter emitter,
            PageContextDTO pageContext
    ) {
        if (decision.actionType() != AgentStepActionType.SUGGEST_WRITE) {
            return null;
        }
        int nextStepNo = run.getUsedSteps() + 1;
        if (observations.isEmpty()) {
            String rejectReason = "首轮零观察写动作提案被拒绝：必须先执行至少一个只读查询";
            emitter.emit(nextStepNo, "SUGGEST_WRITE", "FAILED", "提案被拒绝：需先完成一次查询", null);
            recordRejectedStep(run, decision, nextStepNo, rejectReason);
            observations.add("系统提示：你在没有任何查询结果时尝试提案写动作，后端已拒绝。"
                    + "请先执行只读查询（QUERY_LEARNING_DASHBOARD / QUERY_MEMORY / SEARCH_RAG）再决策。");
            run.setCurrentStep(nextStepNo);
            run.setUsedSteps(nextStepNo);
            run.setContextJson(toJson(clipContext(observations)));
            run.setUpdatedAt(LocalDateTime.now());
            runMapper.updateById(run);
            return null; // 循环继续，由下一轮决策收尾
        }
        return suggestWrite(run, decision, observations, emitter);
    }

    /**
     * SUGGEST_WRITE 终态处理（V2.4 / V3.1）。
     *
     * 双层 actionType：外层动作 SUGGEST_WRITE（已消费），内层 input.actionType 是写动作类型，
     * 缺省回落 UPDATE_TASK_DONE（保旧模型行为）。ADD_LEARNING_TASK 的 taskTitle 是用户点名的新任务
     * （不在观察里），done 无意义忽略。
     * 校验提案（taskTitle 必填；planRef/stageTitle 可选由后端定位；UPDATE 模式 done 缺省 true），
     * run 转 WAITING_WRITE_CONFIRM，提案存 context_json。
     * 不执行任何写操作——执行由 confirmWrite 在用户确认后完成。
     */
    private AgentRunResult suggestWrite(
            AiAgentRun run,
            AgentStepDecision decision,
            List<String> observations,
            AgentStepEmitter emitter
    ) {
        Map<String, Object> input = decision.input() == null ? Map.of() : decision.input();
        String taskTitle = text(input, "taskTitle");
        if (taskTitle == null || taskTitle.isBlank()) {
            emitter.emit(run.getUsedSteps() + 1, "SUGGEST_WRITE", "FAILED", "提案缺少任务标题", null);
            return markFailed(run, "Agent 写动作提案无效（缺少 taskTitle）");
        }
        String actionType = text(input, "actionType");
        // V3.3 归一化四分：blank/null → UPDATE（保旧模型）；UPDATE_TASK_DONE → UPDATE；
        // ADD_LEARNING_TASK → ADD；UPDATE_LEARNING_TASK → RENAME；
        // 其他非空 actionType（模型幻觉出 DELETE/MOVE 等）→ FAILED，绝不吞成勾选提案（误勾选坑）
        String writeType;
        if (actionType == null || actionType.isBlank()
                || AgentWriteProposal.TYPE_UPDATE_TASK_DONE.equals(actionType)) {
            writeType = "UPDATE";
        } else if (AgentWriteProposal.TYPE_ADD_LEARNING_TASK.equals(actionType)) {
            writeType = "ADD";
        } else if (AgentWriteProposal.TYPE_UPDATE_LEARNING_TASK.equals(actionType)) {
            writeType = "RENAME";
        } else {
            emitter.emit(run.getUsedSteps() + 1, "SUGGEST_WRITE", "FAILED", "不支持的写动作类型", null);
            return markFailed(run, "Agent 写动作提案无效（不支持的 actionType：" + actionType + "）");
        }

        // RENAME 专属必填：显式改名但缺新名 → FAILED 绝不回落 UPDATE（回落 + done 缺省 true = 误勾选）
        String newTitle = text(input, "newTitle");
        if ("RENAME".equals(writeType) && (newTitle == null || newTitle.isBlank())) {
            emitter.emit(run.getUsedSteps() + 1, "SUGGEST_WRITE", "FAILED", "提案缺少新任务名", null);
            return markFailed(run, "Agent 写动作提案无效（缺少 newTitle）");
        }
        // V3.1 补充：ADD 提案前预检目标阶段是否已存在同名任务——已存在则不弹确认卡，
        // FAILED step + observation 让 LLM 直接告知用户（原来要等 confirm 才报「已存在」，体验断裂）
        if ("ADD".equals(writeType)) {
            String planRef = text(input, "planRef");
            String stageTitle = text(input, "stageTitle");
            if (stageTitle != null && !stageTitle.isBlank()
                    && targetStageAlreadyHasTask(run.getUserId(), planRef, stageTitle, taskTitle)) {
                int nextStepNo = run.getUsedSteps() + 1;
                String rejectReason = "目标阶段已存在同名任务「" + taskTitle + "」，追加提案被拒绝";
                emitter.emit(nextStepNo, "SUGGEST_WRITE", "FAILED", rejectReason, null);
                recordRejectedStep(run, decision, nextStepNo, rejectReason);
                observations.add("系统提示：目标阶段「" + stageTitle + "」已存在同名任务「" + taskTitle
                        + "」，后端拒绝了追加提案。请直接告知用户该任务已存在（不要生成追加提案，"
                        + "如用户确实要加可建议换成其他任务名或先查看现有任务）。");
                run.setCurrentStep(nextStepNo);
                run.setUsedSteps(nextStepNo);
                run.setContextJson(toJson(clipContext(observations)));
                run.setUpdatedAt(LocalDateTime.now());
                runMapper.updateById(run);
                return null; // 循环继续，由 LLM 收尾告知用户
            }
        }
        // V3.3：RENAME 提案前预检——同名改名（排除自身逻辑必漏，显式拒）+ 新名撞已有任务（排除原任务自身）
        if ("RENAME".equals(writeType)) {
            String planRef = text(input, "planRef");
            String stageTitle = text(input, "stageTitle");
            String rejectReason = null;
            if (newTitle.trim().equalsIgnoreCase(taskTitle.trim())) {
                rejectReason = "新任务名与原任务名相同，改名提案被拒绝";
            } else if (stageTitle != null && !stageTitle.isBlank()
                    && renameNewTitleAlreadyExists(run.getUserId(), planRef, stageTitle, taskTitle, newTitle)) {
                rejectReason = "目标阶段已存在同名任务「" + newTitle + "」，改名提案被拒绝";
            }
            if (rejectReason != null) {
                int nextStepNo = run.getUsedSteps() + 1;
                emitter.emit(nextStepNo, "SUGGEST_WRITE", "FAILED", rejectReason, null);
                recordRejectedStep(run, decision, nextStepNo, rejectReason);
                observations.add("系统提示：" + rejectReason
                        + "。请直接告知用户（不要生成改名提案，如用户确实要改可建议换成其他任务名）。");
                run.setCurrentStep(nextStepNo);
                run.setUsedSteps(nextStepNo);
                run.setContextJson(toJson(clipContext(observations)));
                run.setUpdatedAt(LocalDateTime.now());
                runMapper.updateById(run);
                return null; // 循环继续，由 LLM 收尾告知用户
            }
        }
        boolean done = "UPDATE".equals(writeType) && !"false".equalsIgnoreCase(text(input, "done"));

        AgentWriteProposal proposal = new AgentWriteProposal(
                "ADD".equals(writeType) ? AgentWriteProposal.TYPE_ADD_LEARNING_TASK
                        : "RENAME".equals(writeType) ? AgentWriteProposal.TYPE_UPDATE_LEARNING_TASK
                        : AgentWriteProposal.TYPE_UPDATE_TASK_DONE,
                text(input, "planRef"),
                text(input, "stageTitle"),
                taskTitle,
                done,
                "RENAME".equals(writeType) ? newTitle : null,
                null,
                null
        );

        String actionLabel;
        String finalAnswer;
        if ("ADD".equals(writeType)) {
            actionLabel = "提案追加任务「" + taskTitle + "」到目标阶段";
            finalAnswer = "已为你准备好追加任务「" + taskTitle + "」的提案，确认后执行。";
        } else if ("RENAME".equals(writeType)) {
            actionLabel = "提案将任务「" + taskTitle + "」重命名为「" + newTitle + "」";
            finalAnswer = "已为你准备好将任务「" + taskTitle + "」重命名为「" + newTitle + "」的提案，确认后执行。";
        } else {
            actionLabel = done
                    ? "提案勾选任务「" + taskTitle + "」为完成"
                    : "提案取消任务「" + taskTitle + "」的完成状态";
            finalAnswer = done
                    ? "已为你准备好「" + taskTitle + "」任务勾选提案，确认后执行。"
                    : "已为你准备好「" + taskTitle + "」任务取消勾选提案，确认后执行。";
        }

        emitter.emit(run.getUsedSteps() + 1, "SUGGEST_WRITE", "SUCCESS", actionLabel,
                decision.thoughtSummary());
        recordTerminalStep(run, decision, run.getUsedSteps() + 1);
        run.setStatus(AiAgentRunStatus.WAITING_WRITE_CONFIRM.name());
        run.setFinalAnswer(finalAnswer);
        run.setContextJson(toJson(Map.of(
                "observations", clipContext(observations),
                "pendingWriteAction", proposal
        )));
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
        log.info("Agent Run 写动作提案: runId={}, actionType={}, taskTitle={}, done={}",
                run.getId(), proposal.actionType(), taskTitle, done);
        return AgentRunResult.of(
                run.getId(),
                AiAgentRunStatus.WAITING_WRITE_CONFIRM,
                run.getFinalAnswer(),
                run.getUsedSteps(),
                null,
                proposal
        );
    }

    /**
     * ADD 预检：目标阶段是否已存在同名任务（与 confirm 层重复预检同语义，前置到提案前）。
     * 定位不了（多计划未点名 / 阶段匹配失败 / 读取异常）返回 false——不阻塞提案，
     * confirm 层仍有裁判兜底。
     */
    private boolean targetStageAlreadyHasTask(Long userId, String planRef, String stageTitle, String taskTitle) {
        try {
            List<LearningPlans> actives = learningPlansService.listByUser(userId).stream()
                    .filter(plan -> LearningPlans.STATUS_ACTIVE.equals(plan.getStatus()))
                    .toList();
            if (actives.isEmpty()) {
                return false;
            }
            LearningPlans plan;
            if (planRef != null && !planRef.isBlank()) {
                List<LearningPlans> matched = learningPlansService.matchPlansByMessage(userId, planRef);
                if (matched.size() != 1) {
                    return false;
                }
                plan = matched.get(0);
            } else if (actives.size() == 1) {
                plan = actives.get(0);
            } else {
                return false;
            }
            LearningPlansDetailVO detail = learningPlansService.getDetail(plan.getId(), userId);
            if (detail == null || detail.getStages() == null) {
                return false;
            }
            List<LearningPlansDetailVO.StageProgress> stageMatches = detail.getStages().stream()
                    .filter(stage -> stageTitle.trim().equalsIgnoreCase(
                            stage.getTitle() == null ? "" : stage.getTitle().trim()))
                    .toList();
            if (stageMatches.size() != 1 || stageMatches.get(0).getTasks() == null) {
                return false;
            }
            return stageMatches.get(0).getTasks().stream()
                    .anyMatch(task -> taskTitle.trim().equalsIgnoreCase(
                            task.getTitle() == null ? "" : task.getTitle().trim()));
        } catch (Exception e) {
            log.warn("ADD 重复预检失败，交给 confirm 裁判: userId={}, planRef={}", userId, planRef, e);
            return false;
        }
    }

    /**
     * RENAME 预检（V3.3）：新名是否与目标阶段内其他任务重名（排除被改名的原任务自身及其同名项）。
     * 同名改名边界（newTitle equalsIgnoreCase taskTitle）不在这里判——排除自身逻辑必然漏掉，
     * 由调用点显式拒绝。任何定位歧义 / 异常返回 false——不阻塞提案，confirm 层仍有裁判兜底。
     */
    private boolean renameNewTitleAlreadyExists(Long userId, String planRef, String stageTitle,
                                                String oldTitle, String newTitle) {
        if (oldTitle == null || oldTitle.isBlank() || newTitle == null || newTitle.isBlank()) {
            return false;
        }
        try {
            List<LearningPlans> actives = learningPlansService.listByUser(userId).stream()
                    .filter(plan -> LearningPlans.STATUS_ACTIVE.equals(plan.getStatus()))
                    .toList();
            if (actives.isEmpty()) {
                return false;
            }
            LearningPlans plan;
            if (planRef != null && !planRef.isBlank()) {
                List<LearningPlans> matched = learningPlansService.matchPlansByMessage(userId, planRef);
                if (matched.size() != 1) {
                    return false;
                }
                plan = matched.get(0);
            } else if (actives.size() == 1) {
                plan = actives.get(0);
            } else {
                return false;
            }
            LearningPlansDetailVO detail = learningPlansService.getDetail(plan.getId(), userId);
            if (detail == null || detail.getStages() == null) {
                return false;
            }
            List<LearningPlansDetailVO.StageProgress> stageMatches = detail.getStages().stream()
                    .filter(stage -> stageTitle.trim().equalsIgnoreCase(
                            stage.getTitle() == null ? "" : stage.getTitle().trim()))
                    .toList();
            if (stageMatches.size() != 1 || stageMatches.get(0).getTasks() == null) {
                return false;
            }
            return stageMatches.get(0).getTasks().stream().anyMatch(task -> {
                String title = task.getTitle() == null ? "" : task.getTitle().trim();
                boolean sameAsNew = newTitle.trim().equalsIgnoreCase(title);
                boolean sameAsOld = oldTitle.trim().equalsIgnoreCase(title);
                return sameAsNew && !sameAsOld; // 排除被改名的原任务自身（含其同名项）
            });
        } catch (Exception e) {
            log.warn("RENAME 新名重复预检失败，交给 confirm 裁判: userId={}, planRef={}", userId, planRef, e);
            return false;
        }
    }
}
