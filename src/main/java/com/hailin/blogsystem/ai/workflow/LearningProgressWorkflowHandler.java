package com.hailin.blogsystem.ai.workflow;

import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.dto.*;
import com.hailin.blogsystem.entity.vo.LearningPlansDetailVO;
import com.hailin.blogsystem.service.LearningPlansService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*学习进度工作流（在已有学习计划上调整）：
    create → LOAD_PLAN（加载计划快照，含任务进度 done）
           → ANALYZE_CHANGE（调整诉求不清 → WAITING_REQUIREMENT_CONFIRM 追问）
           → MEMORY_RETRIEVE → RAG_SEARCH → GENERATE_PLAN（旧计划+进度+诉求 → 新计划，质量检查+auto-retry）
           → WAITING_LEARNING_PLAN_CONFIRM
    approve → SAVE_PLAN（按 planId 覆盖，不新建）→ COMPLETED
*/

@Component
public class LearningProgressWorkflowHandler extends AbstractWorkflowHandler {

    //否定反馈："算了/不用了/不想改了..." → 取消 workflow 而不是继续生成
    private static final Pattern NEGATIVE_FEEDBACK = Pattern.compile("(不想改|不改了|不用改|不用了|算了|算了吧|不需要|取消)");
    //选计划反馈里的序号："第2个" / "第二个" / "2"
    private static final Pattern INDEX_PATTERN = Pattern.compile("第?\\s*([一二三四五六七八九十\\d]+)\\s*个?");
    private static final String CHANGE_QUESTION = "你想怎么调整？例如：加个阶段、压缩周期、替换某些任务";

    private final LearningPlanFlowSupport flowSupport;
    private final LearningPlansService learningPlansService;

    public LearningProgressWorkflowHandler(
            WorkflowContextSupport workflowContextSupport,
            WorkflowStatusSupport workflowStatusSupport,
            WorkflowStepRunner workflowStepRunner,
            LearningPlanFlowSupport flowSupport,
            LearningPlansService learningPlansService
    ) {
        super(workflowContextSupport, workflowStatusSupport, workflowStepRunner);
        this.flowSupport = flowSupport;
        this.learningPlansService = learningPlansService;
    }

    @Override
    public String workflowType() {
        return AiWorkflowType.LEARNING_PROGRESS.name();
    }

    /** 可预期业务拒绝 → 友好 AI 回复（真异常返回 null 走 sink.error） */
    @Override
    public String buildRejectedMessage(Throwable e) {
        if (!(e instanceof IllegalArgumentException)) {
            return null;
        }
        String message = e.getMessage();
        if ("学习计划不存在或无权访问".equals(message)) {
            return "找不到你的学习计划，或这个计划不是你的。你可以让我先帮你制定一个新计划。";
        }
        return message == null || message.isBlank() ? "当前计划暂时不能调整。" : message;
    }

    // ==================== create ====================

    public AiWorkflowAdvanceResult create(Long userId, AiWorkflowLearningProgressDTO dto) {
        return create(userId, dto, AiWorkflowStepEmitter.noop());
    }

    //create 只初始化 run（不查库），LOAD_PLAN 在 runInitialSteps 执行（步骤日志记录实际工作）
    public AiWorkflowAdvanceResult create(Long userId, AiWorkflowLearningProgressDTO dto, AiWorkflowStepEmitter emitter) {
        String request = dto == null ? null : dto.getRequest();
        if (workflowContextSupport.isBlank(request)) {
            throw new IllegalArgumentException("调整诉求不能为空");
        }
        Long planId = dto == null ? null : dto.getPlanId();

        Map<String, Object> context = flowSupport.buildInitialContext("");
        workflowContextSupport.getMap(context, "input").put("request", request.trim());
        if (planId != null) {
            context.put("targetPlanId", planId);
        } else if (dto != null && dto.getCandidates() != null && !dto.getCandidates().isEmpty()) {
            //入口点名命中多个计划 → 候选进 context，runInitialSteps 先停确认让用户选
            context.put("planCandidates", dto.getCandidates().stream()
                    .map(candidate -> Map.of(
                            "id", candidate.getId(),
                            "title", candidate.getTitle() == null ? "" : candidate.getTitle()))
                    .toList());
            context.put("awaitingPlanSelection", true);
        } else {
            throw new IllegalArgumentException("学习计划不存在或无权访问");
        }

        AiWorkflowRun run = new AiWorkflowRun();
        run.setUserId(userId);
        run.setConversationId(dto == null ? null : dto.getConversationId());
        run.setWorkflowType(AiWorkflowType.LEARNING_PROGRESS.name());
        run.setWorkflowVersion(LearningPlanFlowSupport.WORKFLOW_VERSION);
        run.setStatus(AiWorkflowStatus.RUNNING.name());
        run.setCurrentStep(AiWorkflowStep.LOAD_PLAN.name());
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

    //LOAD_PLAN → ANALYZE_CHANGE → MEMORY_RETRIEVE → RAG_SEARCH → GENERATE_PLAN → WAITING_LEARNING_PLAN_CONFIRM
    public AiWorkflowAdvanceResult runInitialSteps(AiWorkflowRun run, AiWorkflowStepEmitter emitter) {
        AiWorkflowStepEmitter safeEmitter = emitter == null ? AiWorkflowStepEmitter.noop() : emitter;
        Map<String, Object> context = workflowContextSupport.parseContext(run.getContextJson());
        String request = getRequest(context);

        //0. 选计划阶段：入口点名命中多个计划 → 先停确认让用户选，不等 LOAD_PLAN
        if (Boolean.TRUE.equals(context.get("awaitingPlanSelection"))) {
            return waitForPlanSelection(run, context);
        }

        //1. LOAD_PLAN：加载计划快照（含任务进度），并回填 plan.goal 到 input
        run.setCurrentStep(AiWorkflowStep.LOAD_PLAN.name());
        workflowStepRunner.run(
                run.getId(),
                AiWorkflowStep.LOAD_PLAN,
                "正在加载学习计划...",
                () -> loadPlanSnapshot(run, context),
                safeEmitter
        );
        run.setContextJson(workflowContextSupport.toJson(context));

        //2. ANALYZE_CHANGE：确定性规则判断调整诉求是否具体（LLM 判意图，规则判参数）
        run.setCurrentStep(AiWorkflowStep.ANALYZE_CHANGE.name());
        Boolean clear = workflowStepRunner.run(
                run.getId(),
                AiWorkflowStep.ANALYZE_CHANGE,
                "正在分析调整诉求...",
                () -> !isChangeUnclear(request),
                safeEmitter
        );
        if (!Boolean.TRUE.equals(clear)) {
            //诉求不具体：先检索站内资料（CTA 正文要引用真实文章数），再停等待态
            run.setCurrentStep(AiWorkflowStep.RAG_SEARCH.name());
            List<Map<String, Object>> ragReferences = workflowStepRunner.run(
                    run.getId(),
                    AiWorkflowStep.RAG_SEARCH,
                    "正在检索站内学习资料...",
                    () -> flowSupport.retrieveRagReferences(flowSupport.getGoal(context)),
                    safeEmitter
            );
            context.put("ragContext", Map.of("references", ragReferences));

            workflowContextSupport.putConfirmation(context,
                    AiWorkflowConfirmationType.REQUIREMENT,
                    AiWorkflowStep.ANALYZE_CHANGE.name(),
                    CHANGE_QUESTION);
            run.setStatus(AiWorkflowStatus.WAITING_REQUIREMENT_CONFIRM.name());
            run.setCurrentStep(AiWorkflowStep.ANALYZE_CHANGE.name());
            run.setContextJson(workflowContextSupport.toJson(context));
            workflowContextSupport.touch(run);
            return AiWorkflowAdvanceResult.of(run);
        }

        //3. MEMORY → RAG → GENERATE_PLAN（检索词 = 计划目标 + 调整诉求，含新技术栈）
        flowSupport.runGenerateFlow(run, flowSupport.getGoal(context) + "，" + request, context, safeEmitter);

        //4. 停确认（即使最终质量检查没通过也让用户看到当前结果——用户可 reject 给反馈）
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

    //多计划歧义：停 WAITING_REQUIREMENT_CONFIRM，卡片列候选计划，用户回复序号或计划名
    private AiWorkflowAdvanceResult waitForPlanSelection(AiWorkflowRun run, Map<String, Object> context) {
        workflowContextSupport.putConfirmation(context,
                AiWorkflowConfirmationType.REQUIREMENT,
                AiWorkflowStep.ANALYZE_CHANGE.name(),
                planSelectionQuestion(context, "这次想调整哪个学习计划？请回复序号或计划名："));
        run.setStatus(AiWorkflowStatus.WAITING_REQUIREMENT_CONFIRM.name());
        run.setCurrentStep(AiWorkflowStep.ANALYZE_CHANGE.name());
        run.setContextJson(workflowContextSupport.toJson(context));
        workflowContextSupport.touch(run);
        return AiWorkflowAdvanceResult.of(run);
    }

    //候选计划列表问题文案（首次询问 / 匹配不上提示共用）
    private String planSelectionQuestion(Map<String, Object> context, String prefix) {
        StringBuilder question = new StringBuilder(prefix);
        if (context.get("planCandidates") instanceof List<?> candidates) {
            int index = 1;
            for (Object candidate : candidates) {
                if (candidate instanceof Map<?, ?> c) {
                    question.append("\n").append(index++).append(". 《").append(c.get("title")).append("》");
                }
            }
        }
        return question.toString();
    }

    // ==================== approve ====================

    //WAITING_LEARNING_PLAN_CONFIRM → SAVE_PLAN（按 planId 覆盖）→ COMPLETED
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
                Long targetPlanId = getTargetPlanId(context);
                workflowStepRunner.run(
                        run.getId(),
                        AiWorkflowStep.SAVE_PLAN,
                        "正在更新学习计划...",
                        () -> flowSupport.savePlan(run, context, targetPlanId),
                        emitter
                );

                //计划调整确认已消费：清除卡片，COMPLETED 不再显示确认面板
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
                //选计划阶段：feedback 匹配候选计划 → 确定 targetPlanId 后重跑初始链路
                if (Boolean.TRUE.equals(context.get("awaitingPlanSelection"))) {
                    Long selectedPlanId = matchSelectedPlan(run, context, feedback);
                    if (selectedPlanId != null) {
                        context.put("targetPlanId", selectedPlanId);
                        context.remove("awaitingPlanSelection");
                        run.setContextJson(workflowContextSupport.toJson(context));
                        return runInitialSteps(run, emitter);
                    }
                    //没听懂是哪个计划 → 留在等待态，更新卡片文案明确提示（避免用户以为系统无视）
                    workflowContextSupport.putConfirmation(context,
                            AiWorkflowConfirmationType.REQUIREMENT,
                            AiWorkflowStep.ANALYZE_CHANGE.name(),
                            planSelectionQuestion(context, "没听懂是哪个计划，请回复序号或计划名："));
                    run.setStatus(AiWorkflowStatus.WAITING_REQUIREMENT_CONFIRM.name());
                    run.setCurrentStep(AiWorkflowStep.ANALYZE_CHANGE.name());
                    run.setContextJson(workflowContextSupport.toJson(context));
                    workflowContextSupport.touch(run);
                    return AiWorkflowAdvanceResult.of(run);
                }
                //合并补充信息到调整诉求（原文 + 用户补充的具体要求）
                String merged = (getRequest(context) + "，" + feedback).trim();
                workflowContextSupport.getMap(context, "input").put("request", merged);
                //带着完整诉求重新跑生成流程（不重新判诉求——用户已经给了更多信息）
                flowSupport.runGenerateFlow(run, flowSupport.getGoal(context) + "，" + merged, context, emitter);

                return waitForConfirm(run, context,
                        AiWorkflowStatus.WAITING_LEARNING_PLAN_CONFIRM,
                        AiWorkflowStep.GENERATE_PLAN,
                        AiWorkflowConfirmationType.LEARNING_PLAN);
            }
            case WAITING_LEARNING_PLAN_CONFIRM -> {
                Map<String, Object> plan = workflowStepRunner.run(
                        run.getId(),
                        AiWorkflowStep.GENERATE_PLAN,
                        "正在按意见重新生成计划...",
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

    //初始链路步骤失败 → 重跑初始链路；SAVE_PLAN 失败 → 重跑保存（按 id 覆盖幂等，安全）
    @Override
    protected AiWorkflowAdvanceResult doRetry(
            AiWorkflowRun run,
            AiWorkflowStep step,
            Map<String, Object> context,
            Map<String, Object> stepResults,
            AiWorkflowStepEmitter emitter
    ) {
        switch (step) {
            case LOAD_PLAN, ANALYZE_CHANGE, MEMORY_RETRIEVE, RAG_SEARCH, GENERATE_PLAN -> {
                run.setErrorMessage(null);
                return runInitialSteps(run, emitter);
            }
            case SAVE_PLAN -> {
                workflowStepRunner.run(
                        run.getId(),
                        AiWorkflowStep.SAVE_PLAN,
                        "正在重新更新学习计划...",
                        () -> flowSupport.savePlan(run, context, getTargetPlanId(context)),
                        emitter
                );

                return finish(run, context, AiWorkflowStep.SAVE_PLAN, null);
            }
            default -> throw new IllegalArgumentException("当前步骤不支持重试: " + step);
        }
    }

    // ==================== 步骤实现 ====================

    //查计划详情（Service 内校验归属）→ 构造旧计划快照（含 done）→ 回填 goal
    private boolean loadPlanSnapshot(AiWorkflowRun run, Map<String, Object> context) {
        LearningPlansDetailVO detail = learningPlansService.getDetail(getTargetPlanId(context), run.getUserId());
        context.put("oldPlan", buildOldPlanSnapshot(detail));
        workflowContextSupport.getMap(context, "input").put("goal", detail.getPlan().getGoal());
        return true;
    }

    //详情 VO → context 快照：stages + tasks（title + done），生成 prompt 用
    private Map<String, Object> buildOldPlanSnapshot(LearningPlansDetailVO detail) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("planId", detail.getPlan().getId());
        snapshot.put("title", detail.getPlan().getTitle());

        List<Map<String, Object>> stages = new ArrayList<>();
        for (LearningPlansDetailVO.StageProgress sp : detail.getStages()) {
            Map<String, Object> stage = new HashMap<>();
            stage.put("title", sp.getTitle());
            List<Map<String, Object>> tasks = new ArrayList<>();
            for (LearningPlansDetailVO.TaskItem t : sp.getTasks()) {
                Map<String, Object> task = new HashMap<>();
                task.put("title", t.getTitle());
                task.put("done", t.isDone());
                tasks.add(task);
            }
            stage.put("tasks", tasks);
            stages.add(stage);
        }
        snapshot.put("stages", stages);
        return snapshot;
    }

    // ==================== 业务规则 ====================

    //用户反馈匹配候选计划：序号（"第2个"/"2"/"第二个"）优先，其次计划名打分匹配（与候选交集）。
    // 唯一命中返回 planId；匹配不上返回 null（调用方留在等待态继续追问）
    private Long matchSelectedPlan(AiWorkflowRun run, Map<String, Object> context, String feedback) {
        if (feedback == null || feedback.isBlank()
                || !(context.get("planCandidates") instanceof List<?> candidates)) {
            return null;
        }
        //序号匹配
        Matcher indexMatcher = INDEX_PATTERN.matcher(feedback.trim());
        if (indexMatcher.find()) {
            int index = parseChineseNumber(indexMatcher.group(1));
            if (index >= 1 && index <= candidates.size()
                    && candidates.get(index - 1) instanceof Map<?, ?> candidate) {
                return toLong(candidate.get("id"));
            }
        }
        //计划名打分匹配（最高分并列列表）与候选交集，唯一才确认
        List<LearningPlans> scored = learningPlansService.matchActivePlansByMessage(run.getUserId(), feedback);
        List<Long> hits = new ArrayList<>();
        for (Object candidate : candidates) {
            if (!(candidate instanceof Map<?, ?> c)) {
                continue;
            }
            Long candidateId = toLong(c.get("id"));
            if (candidateId != null && scored.stream().anyMatch(plan -> plan.getId().equals(candidateId))) {
                hits.add(candidateId);
            }
        }
        return hits.size() == 1 ? hits.get(0) : null;
    }

    private int parseChineseNumber(String text) {
        if (text.matches("\\d+")) {
            return Integer.parseInt(text);
        }
        String[] numbers = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};
        for (int i = 1; i < numbers.length; i++) {
            if (numbers[i].equals(text)) {
                return i;
            }
        }
        return -1;
    }

    private Long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    //调整诉求明确性裁判：剥框架词后空/太短/虚词 → 追问具体想怎么调整
    private boolean isChangeUnclear(String request) {
        String text = request == null ? "" : request.trim();
        if (text.isBlank()) {
            return true;
        }
        String topic = text
                .replaceFirst("^(帮我|请帮我|我想|我要|打算|想)", "")
                .replaceFirst("^(把)?(这个|那个|当前|我的)?(学习)?计划", "")
                .replaceFirst("^(调整|改|修改|更新|优化|重排)", "")
                .replaceFirst("^(一下|下)", "")
                .trim();
        if (topic.isBlank() || topic.length() < 2) {
            return true;
        }
        return topic.contains("啥") || topic.contains("随便") || topic.contains("点东西");
    }

    // ==================== context 工具 ====================

    private String getRequest(Map<String, Object> context) {
        return String.valueOf(workflowContextSupport.getMap(context, "input").getOrDefault("request", ""));
    }

    //JSON 数字反序列化可能是 Integer/Long，统一按 Number 转 Long（雪花 id 超 int 范围）
    private Long getTargetPlanId(Map<String, Object> context) {
        Object value = context.get("targetPlanId");
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

}
