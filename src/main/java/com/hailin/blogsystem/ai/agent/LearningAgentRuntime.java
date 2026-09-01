package com.hailin.blogsystem.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.mapper.AiAgentStepMapper;
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

    public LearningAgentRuntime(
            @org.springframework.beans.factory.annotation.Qualifier("llmAgentStepDecider")
            AgentStepDecider decider,
            LearningAgentActionExecutor executor,
            AiAgentRunMapper runMapper,
            AiAgentStepMapper stepMapper,
            ObjectMapper objectMapper
    ) {
        super(executor, runMapper, stepMapper, objectMapper);
        this.decider = decider;
    }

    // ==================== 领域钩子 ====================

    @Override
    protected AgentStepDecider decider() {
        return decider;
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
            emitter.emit(nextStepNo, "SUGGEST_WRITE", "FAILED", "提案被拒绝：需先完成一次查询");
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
     * SUGGEST_WRITE 终态处理（V2.4）。
     *
     * 校验提案（taskTitle 必填；planRef/stageTitle 可选由后端定位；done 缺省 true），
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
            emitter.emit(run.getUsedSteps() + 1, "SUGGEST_WRITE", "FAILED", "提案缺少任务标题");
            return markFailed(run, "Agent 写动作提案无效（缺少 taskTitle）");
        }
        boolean done = !"false".equalsIgnoreCase(text(input, "done"));

        AgentWriteProposal proposal = new AgentWriteProposal(
                "UPDATE_TASK_DONE",
                text(input, "planRef"),
                text(input, "stageTitle"),
                taskTitle,
                done
        );

        emitter.emit(run.getUsedSteps() + 1, "SUGGEST_WRITE", "SUCCESS",
                done ? "提案勾选任务「" + taskTitle + "」为完成" : "提案取消任务「" + taskTitle + "」的完成状态");
        recordTerminalStep(run, decision, run.getUsedSteps() + 1);
        run.setStatus(AiAgentRunStatus.WAITING_WRITE_CONFIRM.name());
        run.setFinalAnswer(done
                ? "已为你准备好「" + taskTitle + "」任务勾选提案，确认后执行。"
                : "已为你准备好「" + taskTitle + "」任务取消勾选提案，确认后执行。");
        run.setContextJson(toJson(Map.of(
                "observations", clipContext(observations),
                "pendingWriteAction", proposal
        )));
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
        log.info("Agent Run 写动作提案: runId={}, taskTitle={}, done={}", run.getId(), taskTitle, done);
        return AgentRunResult.of(
                run.getId(),
                AiAgentRunStatus.WAITING_WRITE_CONFIRM,
                run.getFinalAnswer(),
                run.getUsedSteps(),
                null,
                proposal
        );
    }
}
