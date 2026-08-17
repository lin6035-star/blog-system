package com.hailin.blogsystem.ai.workflow;

import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.dto.*;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

/*学习规划工作流：
    create → ANALYZE_GOAL（信息不足 → WAITING_REQUIREMENT_CONFIRM 追问）
           → MEMORY_RETRIEVE → RAG_SEARCH → GENERATE_PLAN（质量检查+auto-retry）
           → WAITING_LEARNING_PLAN_CONFIRM
    approve → SAVE_PLAN（幂等 upsert）→ COMPLETED
*/

@Component
public class LearningPlanWorkflowHandler extends AbstractWorkflowHandler {

    //否定反馈："算了/不用了/不学了..." → 取消 workflow 而不是继续生成
    private static final Pattern NEGATIVE_FEEDBACK = Pattern.compile("(不想学|不学了|不用了|算了|不需要|不是要|不是想)");
    private static final String GOAL_QUESTION = "你的基础怎么样？计划学多久？";

    private final LearningPlanFlowSupport flowSupport;

    public LearningPlanWorkflowHandler(
            WorkflowContextSupport workflowContextSupport,
            WorkflowStatusSupport workflowStatusSupport,
            WorkflowStepRunner workflowStepRunner,
            LearningPlanFlowSupport flowSupport
    ) {
        super(workflowContextSupport, workflowStatusSupport, workflowStepRunner);
        this.flowSupport = flowSupport;
    }

    @Override
    public String workflowType() {
        return AiWorkflowType.LEARNING_PLAN.name();
    }


    // ==================== create ====================

    public AiWorkflowAdvanceResult create(Long userId, AiWorkflowLearningPlanDTO dto) {
        return create(userId, dto, AiWorkflowStepEmitter.noop());
    }

    //create 只初始化 run（不执行 LLM），runInitialSteps 在 Service save 后调用
    public AiWorkflowAdvanceResult create(Long userId, AiWorkflowLearningPlanDTO dto, AiWorkflowStepEmitter emitter) {
        String goal = dto == null ? null : dto.getGoal();
        if (workflowContextSupport.isBlank(goal)) {
            throw new IllegalArgumentException("学习目标不能为空");
        }

        Map<String, Object> context = flowSupport.buildInitialContext(goal.trim());

        AiWorkflowRun run = new AiWorkflowRun();
        run.setUserId(userId);
        run.setConversationId(dto == null ? null : dto.getConversationId());
        run.setWorkflowType(AiWorkflowType.LEARNING_PLAN.name());
        run.setWorkflowVersion(LearningPlanFlowSupport.WORKFLOW_VERSION);
        run.setStatus(AiWorkflowStatus.RUNNING.name());
        run.setCurrentStep(AiWorkflowStep.ANALYZE_GOAL.name());
        run.setContextJson(workflowContextSupport.toJson(context));
        run.setRetryCount(0);
        run.setInputTokens(0);
        run.setOutputTokens(0);
        run.setTotalTokens(0);
        LocalDateTime now = LocalDateTime.now();
        run.setCreatedAt(now);
        run.setUpdatedAt(now);

        return AiWorkflowAdvanceResult.of(run);
    }

    // ==================== runInitialSteps ====================

    //ANALYZE_GOAL → MEMORY_RETRIEVE → RAG_SEARCH → GENERATE_PLAN → WAITING_LEARNING_PLAN_CONFIRM
    public AiWorkflowAdvanceResult runInitialSteps(AiWorkflowRun run, AiWorkflowStepEmitter emitter) {
        AiWorkflowStepEmitter safeEmitter = emitter == null ? AiWorkflowStepEmitter.noop() : emitter;
        Map<String, Object> context = workflowContextSupport.parseContext(run.getContextJson());
        String goal = flowSupport.getGoal(context);

        //1. ANALYZE_GOAL：确定性规则判断信息是否足够（LLM 判意图，规则判参数）
        run.setCurrentStep(AiWorkflowStep.ANALYZE_GOAL.name());
        Boolean clear = workflowStepRunner.run(
                run.getId(),
                AiWorkflowStep.ANALYZE_GOAL,
                "正在分析学习目标...",
                () -> !isGoalUnclear(goal),
                safeEmitter
        );
        if (!Boolean.TRUE.equals(clear)) {
            //信息不足：先检索站内资料（CTA 正文要引用真实文章数），再停等待态
            run.setCurrentStep(AiWorkflowStep.RAG_SEARCH.name());
            List<Map<String, Object>> ragReferences = workflowStepRunner.run(
                    run.getId(),
                    AiWorkflowStep.RAG_SEARCH,
                    "正在检索站内学习资料...",
                    () -> flowSupport.retrieveRagReferences(goal),
                    safeEmitter
            );
            context.put("ragContext", Map.of("references", ragReferences));

            workflowContextSupport.putConfirmation(context,
                    AiWorkflowConfirmationType.REQUIREMENT,
                    AiWorkflowStep.ANALYZE_GOAL.name(),
                    GOAL_QUESTION);
            run.setStatus(AiWorkflowStatus.WAITING_REQUIREMENT_CONFIRM.name());
            run.setCurrentStep(AiWorkflowStep.ANALYZE_GOAL.name());
            run.setContextJson(workflowContextSupport.toJson(context));
            workflowContextSupport.touch(run);
            return AiWorkflowAdvanceResult.of(run);
        }

        //2. MEMORY_RETRIEVE
        run.setCurrentStep(AiWorkflowStep.MEMORY_RETRIEVE.name());
        String memoryContext = workflowStepRunner.run(
                run.getId(),
                AiWorkflowStep.MEMORY_RETRIEVE,
                "正在读取学习背景...",
                () -> flowSupport.retrieveMemoryContext(run.getUserId(), goal),
                safeEmitter
        );
        context.put("memoryContext", memoryContext);
        run.setContextJson(workflowContextSupport.toJson(context));

        //3. RAG_SEARCH
        run.setCurrentStep(AiWorkflowStep.RAG_SEARCH.name());
        List<Map<String, Object>> ragReferences = workflowStepRunner.run(
                run.getId(),
                AiWorkflowStep.RAG_SEARCH,
                "正在检索站内学习资料...",
                () -> flowSupport.retrieveRagReferences(goal),
                safeEmitter
        );
        context.put("ragContext", Map.of("references", ragReferences));
        run.setContextJson(workflowContextSupport.toJson(context));

        //4. GENERATE_PLAN（异常级 + 检查级双层 auto-retry，见 flowSupport.generateAndCheckPlan）
        run.setCurrentStep(AiWorkflowStep.GENERATE_PLAN.name());
        flowSupport.generateAndCheckPlan(run, context, safeEmitter);

        //停确认（即使最终质量检查没通过也让用户看到当前结果——用户可 reject 给反馈）
        workflowContextSupport.putConfirmation(context,
                AiWorkflowConfirmationType.LEARNING_PLAN,
                AiWorkflowStep.GENERATE_PLAN.name(),
                null);
        run.setStatus(AiWorkflowStatus.WAITING_LEARNING_PLAN_CONFIRM.name());
        run.setCurrentStep(AiWorkflowStep.GENERATE_PLAN.name());
        run.setContextJson(workflowContextSupport.toJson(context));
        workflowContextSupport.touch(run);

        return AiWorkflowAdvanceResult.of(run);
    }

    // ==================== approve ====================

    //WAITING_LEARNING_PLAN_CONFIRM → SAVE_PLAN → COMPLETED
    @Override
    protected AiWorkflowAdvanceResult doApprove(
            AiWorkflowRun run,
            AiWorkflowStatus status,
            Map<String, Object> context,
            Map<String, Object> stepResults,
            AiWorkflowStepEmitter emitter
    ) {
        switch (status) {
            case WAITING_LEARNING_PLAN_CONFIRM -> {
                workflowStepRunner.run(
                        run.getId(),
                        AiWorkflowStep.SAVE_PLAN,
                        "正在保存学习计划...",
                        () -> flowSupport.savePlan(run, context),
                        emitter
                );

                //学习计划确认已消费：清除卡片，COMPLETED 不再显示确认面板
                return finish(run, context, AiWorkflowStep.SAVE_PLAN, null);
            }
            default -> throw new IllegalArgumentException("当前状态不允许同意操作");
        }
    }

    // ==================== reject ====================

    //WAITING_REQUIREMENT_CONFIRM → 否定反馈 → CANCELLED；否则合并补充信息重新生成
    //WAITING_LEARNING_PLAN_CONFIRM → 带意见重新生成计划 → 再确认
    @Override
    protected AiWorkflowAdvanceResult doReject(
            AiWorkflowRun run,
            AiWorkflowStatus status,
            Map<String, Object> context,
            Map<String, Object> stepResults,
            String feedback,
            AiWorkflowStepEmitter emitter
    ) {
        switch (status) {
            case WAITING_REQUIREMENT_CONFIRM -> {
                //否定反馈："算了/不用了" → 取消，不继续生成
                if (NEGATIVE_FEEDBACK.matcher(feedback).find()) {
                    run.setStatus(AiWorkflowStatus.CANCELLED.name());
                    run.setContextJson(workflowContextSupport.toJson(context));
                    workflowContextSupport.touch(run);
                    return AiWorkflowAdvanceResult.of(run);
                }
                //合并补充信息到 goal（原文 + 用户补充的基础/时长）
                String merged = (flowSupport.getGoal(context) + "，" + feedback).trim();
                workflowContextSupport.getMap(context, "input").put("goal", merged);
                //带着完整信息重新跑生成流程（不重新判 goal——用户已经给了更多信息）
                flowSupport.runGenerateFlow(run, merged, context, emitter);

                return waitForConfirm(run, context,
                        AiWorkflowStatus.WAITING_LEARNING_PLAN_CONFIRM,
                        AiWorkflowStep.GENERATE_PLAN,
                        AiWorkflowConfirmationType.LEARNING_PLAN);
            }
            case WAITING_LEARNING_PLAN_CONFIRM -> {
                Map<String, Object> plan = workflowStepRunner.run(
                        run.getId(),
                        AiWorkflowStep.GENERATE_PLAN,
                        "正在按意见重新生成学习计划...",
                        () -> flowSupport.generatePlanByLLM(run, context, emitter),
                        emitter
                );
                workflowContextSupport.getStepResults(context).put("plan", plan);

                return waitForConfirm(run, context,
                        AiWorkflowStatus.WAITING_LEARNING_PLAN_CONFIRM,
                        AiWorkflowStep.GENERATE_PLAN,
                        AiWorkflowConfirmationType.LEARNING_PLAN);
            }
            default -> throw new IllegalArgumentException("当前状态不允许提交修改意见");
        }
    }

    // ==================== retry ====================

    //初始链路步骤失败 → 重跑初始链路；SAVE_PLAN 失败 → 重跑保存（upsert 幂等，安全）
    @Override
    protected AiWorkflowAdvanceResult doRetry(
            AiWorkflowRun run,
            AiWorkflowStep step,
            Map<String, Object> context,
            Map<String, Object> stepResults,
            AiWorkflowStepEmitter emitter
    ) {
        switch (step) {
            case ANALYZE_GOAL, MEMORY_RETRIEVE, RAG_SEARCH, GENERATE_PLAN -> {
                run.setErrorMessage(null);
                return runInitialSteps(run, emitter);
            }
            case SAVE_PLAN -> {
                workflowStepRunner.run(
                        run.getId(),
                        AiWorkflowStep.SAVE_PLAN,
                        "正在重新保存学习计划...",
                        () -> flowSupport.savePlan(run, context),
                        emitter
                );

                return finish(run, context, AiWorkflowStep.SAVE_PLAN, null);
            }
            default -> throw new IllegalArgumentException("当前步骤不支持重试: " + step);
        }
    }

    // ==================== 业务规则 ====================

    //目标明确性裁判（public 供入口复用）：
    //没有规划诉求 或 剥框架词后空/太短/虚词 → 信息不足
    public boolean isGoalUnclear(String goal) {
        String topic = goal == null ? "" : goal.trim();
        if (topic.isBlank()) {
            return true;
        }
        //没有明确规划诉求（"路线/计划/规划/安排"或"帮我+学习"式请求）→ 可能只是随口说说，追问确认
        if (!hasPlanRequest(topic)) {
            return true;
        }
        topic = flowSupport.extractGoalTopic(topic);
        if (topic.isBlank() || topic.length() < 2) {
            return true;
        }
        return topic.contains("啥") || topic.contains("点东西") || topic.contains("随便");
    }

    //不再是"判断用户是否要规划"（那要全覆盖），而是"识别明确到可以跳过确认的表达"。
    //识别不到 → 默认追问（多一轮对话，可接受）；只有高置信强信号才直接生成。
    //防线是追问确认（human-in-the-loop），不是这条规则——规则漏了最多多问一句，不会误生成。
    private boolean hasPlanRequest(String text) {
        return text.matches(".*(学习路线|学习计划|学习规划).*")
                || text.matches(".*(帮我|给我|制定|安排).{0,8}(规划|路线|计划|学习).*");
    }

}
