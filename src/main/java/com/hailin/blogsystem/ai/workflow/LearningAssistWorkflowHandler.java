package com.hailin.blogsystem.ai.workflow;

import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.LearningStages;
import com.hailin.blogsystem.entity.dto.*;
import com.hailin.blogsystem.entity.vo.LearningPlansDetailVO;
import com.hailin.blogsystem.service.LearningPlansService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*学习难点攻坚工作流（在已有学习计划某阶段卡住时拆解）：
    create → LOAD_PLAN（加载计划标题 + 阶段目录，含任务进度）
           → LOCATE_STAGE（定位难点所在阶段，定位不了 → WAITING_REQUIREMENT_CONFIRM 列阶段追问）
           → MEMORY_RETRIEVE → RAG_SEARCH → GENERATE_TASKS（LLM 拆解 3-5 个新任务点，质量检查+auto-retry）
           → WAITING_LEARNING_PLAN_CONFIRM
    approve → APPEND_TASKS（任务点追加到目标阶段，去重幂等，其他阶段不动）→ COMPLETED
*/

@Component
public class LearningAssistWorkflowHandler extends AbstractWorkflowHandler {

    //否定反馈："算了/不用了..." → 取消 workflow 而不是继续生成
    private static final Pattern NEGATIVE_FEEDBACK = Pattern.compile("(不想改|不改了|不用改|不用了|算了|算了吧|不需要|取消)");
    //选计划/选阶段反馈里的序号："第2个" / "第二个" / "2"
    private static final Pattern INDEX_PATTERN = Pattern.compile("第?\\s*([一二三四五六七八九十\\d]+)\\s*个?");

    private final LearningPlanFlowSupport flowSupport;
    private final LearningPlansService learningPlansService;

    public LearningAssistWorkflowHandler(
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
        return AiWorkflowType.LEARNING_ASSIST.name();
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
        return message == null || message.isBlank() ? "这个难点暂时帮不上忙，换个说法试试。" : message;
    }

    // ==================== create ====================

    public AiWorkflowAdvanceResult create(Long userId, AiWorkflowLearningAssistDTO dto) {
        return create(userId, dto, AiWorkflowStepEmitter.noop());
    }

    //create 只初始化 run（不查库），LOAD_PLAN 在 runInitialSteps 执行（步骤日志记录实际工作）
    public AiWorkflowAdvanceResult create(Long userId, AiWorkflowLearningAssistDTO dto, AiWorkflowStepEmitter emitter) {
        String request = dto == null ? null : dto.getRequest();
        if (workflowContextSupport.isBlank(request)) {
            throw new IllegalArgumentException("难点诉求不能为空");
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
        run.setWorkflowType(AiWorkflowType.LEARNING_ASSIST.name());
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

    //LOAD_PLAN → LOCATE_STAGE → MEMORY_RETRIEVE → RAG_SEARCH → GENERATE_TASKS → WAITING_LEARNING_PLAN_CONFIRM
    public AiWorkflowAdvanceResult runInitialSteps(AiWorkflowRun run, AiWorkflowStepEmitter emitter) {
        AiWorkflowStepEmitter safeEmitter = emitter == null ? AiWorkflowStepEmitter.noop() : emitter;
        Map<String, Object> context = workflowContextSupport.parseContext(run.getContextJson());
        String request = getRequest(context);

        //0. 选计划阶段：入口点名命中多个计划 → 先停确认让用户选，不等 LOAD_PLAN
        if (Boolean.TRUE.equals(context.get("awaitingPlanSelection"))) {
            return waitForPlanSelection(run, context);
        }

        //1. LOAD_PLAN：加载计划标题 + 阶段目录（含任务进度，阶段带 id 供 APPEND_TASKS 定位）
        run.setCurrentStep(AiWorkflowStep.LOAD_PLAN.name());
        workflowStepRunner.run(
                run.getId(),
                AiWorkflowStep.LOAD_PLAN,
                "正在加载学习计划...",
                () -> loadStageCatalog(run, context),
                safeEmitter
        );
        run.setContextJson(workflowContextSupport.toJson(context));

        //2. LOCATE_STAGE：确定性规则定位难点所在阶段（消息词段 vs 阶段/任务标题打分）
        run.setCurrentStep(AiWorkflowStep.LOCATE_STAGE.name());
        Boolean located = workflowStepRunner.run(
                run.getId(),
                AiWorkflowStep.LOCATE_STAGE,
                "正在定位难点阶段...",
                () -> locateStage(run, context),
                safeEmitter
        );
        if (!Boolean.TRUE.equals(located)) {
            //定位不了 → 列全部阶段候选追问（单阶段计划在 locateStage 里已直接定位）
            return waitForStageSelection(run, context, "这个难点在哪个阶段？请回复序号或阶段名：");
        }

        return runAfterStageLocated(run, context, safeEmitter);
    }

    //阶段已定位：MEMORY → RAG → GENERATE_TASKS → 停确认
    private AiWorkflowAdvanceResult runAfterStageLocated(AiWorkflowRun run, Map<String, Object> context, AiWorkflowStepEmitter emitter) {
        String request = getRequest(context);

        run.setCurrentStep(AiWorkflowStep.MEMORY_RETRIEVE.name());
        String memoryContext = workflowStepRunner.run(
                run.getId(), AiWorkflowStep.MEMORY_RETRIEVE, "正在读取学习背景...",
                () -> flowSupport.retrieveMemoryContext(run.getUserId(), request), emitter);
        context.put("memoryContext", memoryContext);

        run.setCurrentStep(AiWorkflowStep.RAG_SEARCH.name());
        List<Map<String, Object>> ragReferences = workflowStepRunner.run(
                run.getId(), AiWorkflowStep.RAG_SEARCH, "正在检索站内学习资料...",
                () -> flowSupport.retrieveRagReferences(request), emitter);
        context.put("ragContext", Map.of("references", ragReferences));

        run.setCurrentStep(AiWorkflowStep.GENERATE_TASKS.name());
        flowSupport.generateAndCheckBreakdown(run, context, getTargetStageExistingTitles(context), emitter);

        //停确认（即使最终质量检查没通过也让用户看到当前结果——用户可 reject 给反馈）
        return waitForConfirm(run, context,
                AiWorkflowStatus.WAITING_LEARNING_PLAN_CONFIRM,
                AiWorkflowStep.GENERATE_TASKS,
                AiWorkflowConfirmationType.LEARNING_PLAN);
    }

    //多计划歧义：停 WAITING_REQUIREMENT_CONFIRM，卡片列候选计划，用户回复序号或计划名
    private AiWorkflowAdvanceResult waitForPlanSelection(AiWorkflowRun run, Map<String, Object> context) {
        workflowContextSupport.putConfirmation(context,
                AiWorkflowConfirmationType.REQUIREMENT,
                AiWorkflowStep.LOCATE_STAGE.name(),
                planSelectionQuestion(context, "这次想攻克哪个学习计划？请回复序号或计划名："));
        run.setStatus(AiWorkflowStatus.WAITING_REQUIREMENT_CONFIRM.name());
        run.setCurrentStep(AiWorkflowStep.LOCATE_STAGE.name());
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

    //定位不了：awaitingStageSelection 旗标 + 全部阶段候选，停 REQUIREMENT 确认
    private AiWorkflowAdvanceResult waitForStageSelection(AiWorkflowRun run, Map<String, Object> context, String prefix) {
        context.put("awaitingStageSelection", true);
        context.put("stageCandidates", buildStageCandidates(context));
        workflowContextSupport.putConfirmation(context,
                AiWorkflowConfirmationType.REQUIREMENT,
                AiWorkflowStep.LOCATE_STAGE.name(),
                stageSelectionQuestion(context, prefix));
        run.setStatus(AiWorkflowStatus.WAITING_REQUIREMENT_CONFIRM.name());
        run.setCurrentStep(AiWorkflowStep.LOCATE_STAGE.name());
        run.setContextJson(workflowContextSupport.toJson(context));
        workflowContextSupport.touch(run);
        return AiWorkflowAdvanceResult.of(run);
    }

    //候选阶段列表问题文案（首次询问 / 匹配不上提示共用）
    private String stageSelectionQuestion(Map<String, Object> context, String prefix) {
        StringBuilder question = new StringBuilder(prefix);
        List<Map<String, Object>> candidates = getStageCandidateList(context);
        int index = 1;
        for (Map<String, Object> candidate : candidates) {
            question.append("\n").append(index++).append(". 《").append(candidate.get("title")).append("》");
        }
        return question.toString();
    }

    // ==================== approve ====================

    //WAITING_LEARNING_PLAN_CONFIRM → APPEND_TASKS（追加到目标阶段，去重幂等）→ COMPLETED
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
                Long targetStageId = getTargetStageId(context);
                if (targetStageId == null) {
                    throw new IllegalArgumentException("未定位到学习阶段");
                }
                List<String> taskTitles = flowSupport.extractTaskTitles(workflowContextSupport.getMap(stepResults, "plan"));
                workflowStepRunner.run(
                        run.getId(),
                        AiWorkflowStep.APPEND_TASKS,
                        "正在追加任务点...",
                        () -> {
                            learningPlansService.appendTasks(targetPlanId, targetStageId, taskTitles, run.getUserId());
                            return true;
                        },
                        emitter
                );

                //任务点已落计划：清除卡片，COMPLETED 不再显示确认面板
                return finish(run, context, AiWorkflowStep.APPEND_TASKS, null);
            }
            default -> throw new IllegalArgumentException("当前状态不允许同意操作");
        }
    }

    // ==================== reject ====================

    //WAITING_REQUIREMENT_CONFIRM → 否定反馈 → CANCELLED；选计划/选阶段反馈 → 确定目标后继续；匹配不上留在等待态
    //WAITING_LEARNING_PLAN_CONFIRM → 带意见重新拆解 → 再确认
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
                        //定位反馈（"第二个"/计划名）不是生成意见，不进生成 prompt
                        context.put("feedbackHistory", new ArrayList<>());
                        run.setContextJson(workflowContextSupport.toJson(context));
                        return runInitialSteps(run, emitter);
                    }
                    //没听懂是哪个计划 → 留在等待态，更新卡片文案明确提示
                    workflowContextSupport.putConfirmation(context,
                            AiWorkflowConfirmationType.REQUIREMENT,
                            AiWorkflowStep.LOCATE_STAGE.name(),
                            planSelectionQuestion(context, "没听懂是哪个计划，请回复序号或计划名："));
                    run.setStatus(AiWorkflowStatus.WAITING_REQUIREMENT_CONFIRM.name());
                    run.setCurrentStep(AiWorkflowStep.LOCATE_STAGE.name());
                    run.setContextJson(workflowContextSupport.toJson(context));
                    workflowContextSupport.touch(run);
                    return AiWorkflowAdvanceResult.of(run);
                }
                //选阶段阶段：feedback 匹配候选阶段 → 确定 targetStageId 后继续生成
                if (Boolean.TRUE.equals(context.get("awaitingStageSelection"))) {
                    Long selectedStageId = matchSelectedStage(run, context, feedback);
                    if (selectedStageId != null) {
                        context.put("targetStageId", selectedStageId);
                        context.put("targetStageTitle", stageTitleOf(context, selectedStageId));
                        context.remove("awaitingStageSelection");
                        context.remove("stageCandidates");
                        //定位反馈（"第二个"/阶段名）不是生成意见，不进生成 prompt
                        context.put("feedbackHistory", new ArrayList<>());
                        run.setContextJson(workflowContextSupport.toJson(context));
                        return runAfterStageLocated(run, context, emitter);
                    }
                    //没听懂是哪个阶段 → 留在等待态，更新卡片文案明确提示
                    workflowContextSupport.putConfirmation(context,
                            AiWorkflowConfirmationType.REQUIREMENT,
                            AiWorkflowStep.LOCATE_STAGE.name(),
                            stageSelectionQuestion(context, "没听懂是哪个阶段，请回复序号或阶段名："));
                    run.setStatus(AiWorkflowStatus.WAITING_REQUIREMENT_CONFIRM.name());
                    run.setCurrentStep(AiWorkflowStep.LOCATE_STAGE.name());
                    run.setContextJson(workflowContextSupport.toJson(context));
                    workflowContextSupport.touch(run);
                    return AiWorkflowAdvanceResult.of(run);
                }
                throw new IllegalArgumentException("当前状态不允许提交修改意见");
            }
            case WAITING_LEARNING_PLAN_CONFIRM -> {
                Map<String, Object> plan = workflowStepRunner.run(
                        run.getId(),
                        AiWorkflowStep.GENERATE_TASKS,
                        "正在按意见重新拆解...",
                        () -> flowSupport.generateBreakdownByLLM(run, context, emitter),
                        emitter
                );
                workflowContextSupport.getStepResults(context).put("plan", plan);
                workflowContextSupport.getStepResults(context).put("qualityCheck",
                        flowSupport.buildBreakdownQualityCheck(plan, getTargetStageExistingTitles(context)));

                return waitForConfirm(run, context,
                        AiWorkflowStatus.WAITING_LEARNING_PLAN_CONFIRM,
                        AiWorkflowStep.GENERATE_TASKS,
                        AiWorkflowConfirmationType.LEARNING_PLAN);
            }
            default -> throw new IllegalArgumentException("当前状态不允许提交修改意见");
        }
    }

    // ==================== retry ====================

    //初始链路步骤失败 → 重跑初始链路；APPEND_TASKS 失败 → 重跑追加（去重过滤天然幂等）
    @Override
    protected AiWorkflowAdvanceResult doRetry(
            AiWorkflowRun run,
            AiWorkflowStep step,
            Map<String, Object> context,
            Map<String, Object> stepResults,
            AiWorkflowStepEmitter emitter
    ) {
        switch (step) {
            case LOAD_PLAN, LOCATE_STAGE, MEMORY_RETRIEVE, RAG_SEARCH, GENERATE_TASKS -> {
                run.setErrorMessage(null);
                return runInitialSteps(run, emitter);
            }
            case APPEND_TASKS -> {
                Long targetPlanId = getTargetPlanId(context);
                Long targetStageId = getTargetStageId(context);
                if (targetStageId == null) {
                    throw new IllegalArgumentException("未定位到学习阶段");
                }
                List<String> taskTitles = flowSupport.extractTaskTitles(workflowContextSupport.getMap(stepResults, "plan"));
                workflowStepRunner.run(
                        run.getId(),
                        AiWorkflowStep.APPEND_TASKS,
                        "正在重新追加任务点...",
                        () -> {
                            learningPlansService.appendTasks(targetPlanId, targetStageId, taskTitles, run.getUserId());
                            return true;
                        },
                        emitter
                );

                return finish(run, context, AiWorkflowStep.APPEND_TASKS, null);
            }
            default -> throw new IllegalArgumentException("当前步骤不支持重试: " + step);
        }
    }

    // ==================== 步骤实现 ====================

    //查计划详情（Service 内校验归属）→ context 放 planTitle + stageCatalog（阶段带 id + 任务进度）。
    // 不写 oldPlan 键：攻坚的生成 prompt 不需要整体计划，且避免误触 flowSupport.isAdjustMode
    private boolean loadStageCatalog(AiWorkflowRun run, Map<String, Object> context) {
        LearningPlansDetailVO detail = learningPlansService.getDetail(getTargetPlanId(context), run.getUserId());
        context.put("planTitle", detail.getPlan().getTitle());

        List<Map<String, Object>> catalog = new ArrayList<>();
        for (LearningPlansDetailVO.StageProgress sp : detail.getStages()) {
            Map<String, Object> stage = new HashMap<>();
            stage.put("id", sp.getId());
            stage.put("title", sp.getTitle());
            List<Map<String, Object>> tasks = new ArrayList<>();
            for (LearningPlansDetailVO.TaskItem t : sp.getTasks()) {
                Map<String, Object> task = new HashMap<>();
                task.put("title", t.getTitle());
                task.put("done", t.isDone());
                tasks.add(task);
            }
            stage.put("tasks", tasks);
            catalog.add(stage);
        }
        context.put("stageCatalog", catalog);
        return true;
    }

    //定位难点阶段：单阶段计划直接进；否则消息分词 vs 阶段/任务标题打分，唯一最高分命中。
    // 返回 false = 定位不了（调用方列全部阶段候选追问）
    private boolean locateStage(AiWorkflowRun run, Map<String, Object> context) {
        List<Map<String, Object>> catalog = getStageCatalog(context);
        if (catalog.size() == 1) {
            //单阶段计划：无选择余地，直接定位
            context.put("targetStageId", toLong(catalog.get(0).get("id")));
            context.put("targetStageTitle", String.valueOf(catalog.get(0).getOrDefault("title", "")));
            return true;
        }
        List<LearningStages> matched = learningPlansService.matchStagesByMessage(
                getTargetPlanId(context), run.getUserId(), getRequest(context));
        if (matched.size() == 1) {
            context.put("targetStageId", matched.get(0).getId());
            context.put("targetStageTitle", matched.get(0).getTitle());
            return true;
        }
        return false;
    }

    // ==================== 业务规则 ====================

    //用户反馈匹配候选计划：序号优先，其次计划名打分匹配（与候选交集），唯一命中返回 planId
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

    //用户反馈匹配候选阶段：序号优先（stageCandidates 列表序），其次阶段名打分匹配（与候选交集）
    private Long matchSelectedStage(AiWorkflowRun run, Map<String, Object> context, String feedback) {
        if (feedback == null || feedback.isBlank()) {
            return null;
        }
        List<Map<String, Object>> candidates = getStageCandidateList(context);
        if (candidates.isEmpty()) {
            return null;
        }
        //序号匹配
        Matcher indexMatcher = INDEX_PATTERN.matcher(feedback.trim());
        if (indexMatcher.find()) {
            int index = parseChineseNumber(indexMatcher.group(1));
            if (index >= 1 && index <= candidates.size()) {
                return toLong(candidates.get(index - 1).get("id"));
            }
        }
        //阶段名打分匹配（最高分并列列表）与候选交集，唯一才确认
        List<LearningStages> scored = learningPlansService.matchStagesByMessage(
                getTargetPlanId(context), run.getUserId(), feedback);
        List<Long> hits = new ArrayList<>();
        for (Map<String, Object> candidate : candidates) {
            Long candidateId = toLong(candidate.get("id"));
            if (candidateId != null && scored.stream().anyMatch(stage -> stage.getId().equals(candidateId))) {
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

    private Long getTargetStageId(Map<String, Object> context) {
        Object value = context.get("targetStageId");
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    //stageCatalog = [{id, title, tasks:[{title,done}]}]
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getStageCatalog(Map<String, Object> context) {
        Object catalog = context.get("stageCatalog");
        return catalog instanceof List<?> list
                ? (List<Map<String, Object>>) list.stream()
                    .filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList()
                : new ArrayList<>();
    }

    //候选阶段列表：优先用 context.stageCandidates（选阶段阶段），否则用 stageCatalog
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getStageCandidateList(Map<String, Object> context) {
        Object candidates = context.get("stageCandidates");
        if (candidates instanceof List<?> list && !list.isEmpty()) {
            return list.stream().filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item).toList();
        }
        return getStageCatalog(context);
    }

    private List<Map<String, Object>> buildStageCandidates(Map<String, Object> context) {
        return getStageCatalog(context).stream()
                .map(stage -> Map.<String, Object>of("id", stage.get("id"), "title", stage.get("title")))
                .toList();
    }

    private String stageTitleOf(Map<String, Object> context, Long stageId) {
        for (Map<String, Object> stage : getStageCatalog(context)) {
            if (stageId.equals(toLong(stage.get("id")))) {
                return String.valueOf(stage.getOrDefault("title", ""));
            }
        }
        return "";
    }

    //目标阶段已有任务标题列表（质量检查 / 生成 prompt 禁止重复用）
    private List<String> getTargetStageExistingTitles(Map<String, Object> context) {
        Long stageId = getTargetStageId(context);
        List<String> titles = new ArrayList<>();
        if (stageId == null) {
            return titles;
        }
        for (Map<String, Object> stage : getStageCatalog(context)) {
            if (!stageId.equals(toLong(stage.get("id")))) {
                continue;
            }
            if (stage.get("tasks") instanceof List<?> tasks) {
                for (Object t : tasks) {
                    if (t instanceof Map<?, ?> task) {
                        Object titleObj = task.get("title");
                        titles.add(titleObj == null ? "" : String.valueOf(titleObj));
                    }
                }
            }
            break;
        }
        return titles;
    }

}
