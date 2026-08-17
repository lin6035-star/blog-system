package com.hailin.blogsystem.ai.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.LlmErrorClassifier;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.LearningStages;
import com.hailin.blogsystem.entity.dto.AiWorkflowStatus;
import com.hailin.blogsystem.entity.dto.AiWorkflowStep;
import com.hailin.blogsystem.service.LearningPlansService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 学习规划类 Workflow 共享业务流程（Support = 纯工具，不做生命周期）。
 * LEARNING_PLAN（新建计划）与 LEARNING_PROGRESS（调整已有计划）复用：
 * 三段生成流 / 计划生成 + 质量检查 + auto-retry / 幂等保存 / prompt 与 JSON 工具。
 *
 * 调整模式由 context 驱动，调用方零差异：
 *   context 含 oldPlan → prompt 自动附加"当前计划与进度" + 保留已完成任务约束
 *   savePlan 传 targetPlanId → 按 planId 覆盖而非新建
 */
@Component
public class LearningPlanFlowSupport {

    //学习规划类 Workflow 统一版本号
    public static final String WORKFLOW_VERSION = "1.0";

    //调整模式系统约束（context 含 oldPlan 时拼到生成 prompt 后面）
    private static final String ADJUST_CONSTRAINT = """
            5. 这是对现有计划的调整：已完成任务（done=true）必须完整保留，
               其余部分按调整诉求修改。每个任务必须是 {"title":"...","done":true/false} 格式
            """;

    private final WorkflowStepRunner workflowStepRunner;
    private final WorkflowContextSupport workflowContextSupport;
    private final LlmStreamCaller llmStreamCaller;
    private final WorkflowTokenRecorder workflowTokenRecorder;
    private final WorkflowQualitySupport workflowQualitySupport;
    private final WorkflowKnowledgeSupport workflowKnowledgeSupport;
    private final ObjectMapper objectMapper;
    private final LearningPlansService learningPlansService;

    public LearningPlanFlowSupport(
            WorkflowStepRunner workflowStepRunner,
            WorkflowContextSupport workflowContextSupport,
            LlmStreamCaller llmStreamCaller,
            WorkflowTokenRecorder workflowTokenRecorder,
            WorkflowQualitySupport workflowQualitySupport,
            WorkflowKnowledgeSupport workflowKnowledgeSupport,
            ObjectMapper objectMapper,
            LearningPlansService learningPlansService
    ) {
        this.workflowStepRunner = workflowStepRunner;
        this.workflowContextSupport = workflowContextSupport;
        this.llmStreamCaller = llmStreamCaller;
        this.workflowTokenRecorder = workflowTokenRecorder;
        this.workflowQualitySupport = workflowQualitySupport;
        this.workflowKnowledgeSupport = workflowKnowledgeSupport;
        this.objectMapper = objectMapper;
        this.learningPlansService = learningPlansService;
    }

    // ==================== 生成流（MEMORY → RAG → GENERATE_PLAN） ====================

    //reject 补充信息 / 调整诉求确认后重跑的三段（两个 Handler 共用）
    public void runGenerateFlow(AiWorkflowRun run, String goal, Map<String, Object> context, AiWorkflowStepEmitter emitter) {
        run.setCurrentStep(AiWorkflowStep.MEMORY_RETRIEVE.name());
        String memoryContext = workflowStepRunner.run(
                run.getId(), AiWorkflowStep.MEMORY_RETRIEVE, "正在读取学习背景...",
                () -> retrieveMemoryContext(run.getUserId(), goal), emitter);
        context.put("memoryContext", memoryContext);

        run.setCurrentStep(AiWorkflowStep.RAG_SEARCH.name());
        List<Map<String, Object>> ragReferences = workflowStepRunner.run(
                run.getId(), AiWorkflowStep.RAG_SEARCH, "正在检索站内学习资料...",
                () -> retrieveRagReferences(goal), emitter);
        context.put("ragContext", Map.of("references", ragReferences));

        run.setCurrentStep(AiWorkflowStep.GENERATE_PLAN.name());
        generateAndCheckPlan(run, context, emitter);
    }

    // ==================== 计划生成 + 质量检查 ====================

    //生成计划 + 质量检查 + 双层 auto-retry：
    //异常级一次（解析失败/空内容/网络抖动）+ 检查级一次（结构不合规喂回模型）
    public Map<String, Object> generateAndCheckPlan(AiWorkflowRun run, Map<String, Object> context, AiWorkflowStepEmitter emitter) {
        Map<String, Object> plan = generatePlanWithRetry(run, context, emitter);
        Map<String, Object> check = buildPlanQualityCheck(plan);
        if (Boolean.FALSE.equals(check.get("passed"))) {
            String systemFeedback = workflowQualitySupport.buildQualityFeedbackForModel(check, "学习计划（阶段 3-8 个，每阶段任务 2-8 个，任务具体可执行，无重复，只输出 JSON）");
            workflowContextSupport.appendFeedback(context, AiWorkflowStep.GENERATE_PLAN.name(), AiWorkflowStatus.RUNNING.name(), systemFeedback);
            plan = workflowStepRunner.run(
                    run.getId(),
                    AiWorkflowStep.GENERATE_PLAN,
                    "计划未通过检查，正在重新生成...",
                    () -> generatePlanByLLM(run, context, emitter),
                    emitter
            );
            check = buildPlanQualityCheck(plan);
        }
        workflowContextSupport.getStepResults(context).put("plan", plan);
        workflowContextSupport.getStepResults(context).put("qualityCheck", check);
        return plan;
    }

    //第一次生成抛异常（解析失败/空内容/网络抖动）→ 自动重试一次；再失败则抛出走 FAILED + 手动重试
    public Map<String, Object> generatePlanWithRetry(AiWorkflowRun run, Map<String, Object> context, AiWorkflowStepEmitter emitter) {
        try {
            return workflowStepRunner.run(
                    run.getId(),
                    AiWorkflowStep.GENERATE_PLAN,
                    "正在生成学习计划...",
                    () -> generatePlanByLLM(run, context, emitter),
                    emitter
            );
        } catch (RuntimeException e) {
            return workflowStepRunner.run(
                    run.getId(),
                    AiWorkflowStep.GENERATE_PLAN,
                    "计划生成失败，正在重新生成...",
                    () -> generatePlanByLLM(run, context, emitter),
                    emitter
            );
        }
    }

    //LLM 生成结构化计划 JSON；调整模式（context 含 oldPlan）自动附加保留进度约束
    public Map<String, Object> generatePlanByLLM(AiWorkflowRun run, Map<String, Object> context, AiWorkflowStepEmitter emitter) {
        try {
            String systemPrompt = """
                    你是学习规划专家。输出纯 JSON，不要任何解释、代码块或前后缀。
                    JSON 格式：
                    {"title":"计划标题","stages":[{"title":"阶段标题","tasks":["任务1","任务2"]}]}
                    约束：
                    1. 阶段 3-8 个，按学习顺序排列
                    2. 每阶段任务 2-8 个，任务要具体可执行（不是口号）
                    3. 结合用户目标、长期记忆中的背景偏好、站内文章参考
                    4. 事实边界：不编造站内不存在的课程/资料，任务难度符合用户基础
                    """ + (isAdjustMode(context) ? ADJUST_CONSTRAINT : "");

            LlmStreamCaller.LlmStreamResult result = llmStreamCaller.call(
                    "学习计划生成失败：",
                    AiWorkflowStep.GENERATE_PLAN,
                    "plan",
                    emitter,
                    systemPrompt,
                    buildPlanUserPrompt(context),
                    null
            );
            workflowTokenRecorder.accumulate(run, result.usage());
            return parsePlanJson(cleanJson(result.content()));
        } catch (RuntimeException e) {
            throw LlmErrorClassifier.wrap("学习计划生成失败：", e);
        } catch (Exception e) {
            throw new RuntimeException("学习计划生成失败：" + LlmErrorClassifier.friendlyMessage(e), e);
        }
    }

    //context 含 oldPlan（非空 Map）→ 调整模式
    public boolean isAdjustMode(Map<String, Object> context) {
        return context.get("oldPlan") instanceof Map<?, ?> m && !m.isEmpty();
    }

    //规则质量检查：阶段数、任务数、非空、全局去重
    public Map<String, Object> buildPlanQualityCheck(Map<String, Object> plan) {
        List<String> issues = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();

        Object stagesObj = plan == null ? null : plan.get("stages");
        if (!(stagesObj instanceof List<?> stagesList) || stagesList.isEmpty()) {
            issues.add("学习计划没有阶段");
        } else if (stagesList.size() < 3 || stagesList.size() > 8) {
            issues.add("阶段数量应在 3-8 个，当前 " + stagesList.size() + " 个");
        }

        Set<String> seenTasks = new HashSet<>();
        if (stagesObj instanceof List<?> stagesList) {
            int stageNo = 1;
            for (Object s : stagesList) {
                if (!(s instanceof Map<?, ?> stageMap)) {
                    continue;
                }
                Object tasksObj = stageMap.get("tasks");
                if (!(tasksObj instanceof List<?> taskList)) {
                    issues.add("阶段" + stageNo + " 没有任务列表");
                    stageNo++;
                    continue;
                }
                if (taskList.size() < 2 || taskList.size() > 8) {
                    issues.add("阶段" + stageNo + " 的任务数量应在 2-8 个，当前 " + taskList.size() + " 个");
                }
                for (Object t : taskList) {
                    String title;
                    if (t instanceof Map<?, ?> m) {
                        Object mTitle = m.get("title");
                        title = mTitle == null ? "" : String.valueOf(mTitle);
                    } else {
                        title = String.valueOf(t);
                    }
                    if (title.isBlank()) {
                        issues.add("阶段" + stageNo + " 存在空任务标题");
                    } else if (!seenTasks.add(title)) {
                        issues.add("任务重复：" + title);
                    }
                }
                stageNo++;
            }
        }

        Map<String, Object> check = new HashMap<>();
        check.put("passed", issues.isEmpty());
        check.put("issues", issues);
        check.put("suggestions", suggestions);
        return check;
    }

    // ==================== 难点攻坚（LEARNING_ASSIST）：拆解生成 + 质量检查 ====================

    //生成拆解 + 质量检查 + 检查级 auto-retry 一次（照 generateAndCheckPlan 模式）。
    // existingTitles = 目标阶段已有任务标题（新任务点不得重复），结果进 stepResults.plan/qualityCheck
    public Map<String, Object> generateAndCheckBreakdown(AiWorkflowRun run, Map<String, Object> context,
                                                          List<String> existingTitles, AiWorkflowStepEmitter emitter) {
        Map<String, Object> plan = generateBreakdownWithRetry(run, context, emitter);
        plan = normalizeBreakdownPlan(plan, existingTitles);
        Map<String, Object> check = buildBreakdownQualityCheck(plan, existingTitles);
        if (Boolean.FALSE.equals(check.get("passed"))) {
            String systemFeedback = workflowQualitySupport.buildQualityFeedbackForModel(check,
                    "难点拆解（任务 3-5 个，具体可执行，不与已有任务重复，只输出 JSON）");
            workflowContextSupport.appendFeedback(context, AiWorkflowStep.GENERATE_TASKS.name(), AiWorkflowStatus.RUNNING.name(), systemFeedback);
            plan = workflowStepRunner.run(
                    run.getId(),
                    AiWorkflowStep.GENERATE_TASKS,
                    "拆解未通过检查，正在重新生成...",
                    () -> generateBreakdownByLLM(run, context, emitter),
                    emitter
            );
            plan = normalizeBreakdownPlan(plan, existingTitles);
            check = buildBreakdownQualityCheck(plan, existingTitles);
        }
        workflowContextSupport.getStepResults(context).put("plan", plan);
        workflowContextSupport.getStepResults(context).put("qualityCheck", check);
        return plan;
    }

    //第一次生成抛异常（解析失败/空内容/网络抖动）→ 自动重试一次；再失败则抛出走 FAILED + 手动重试
    public Map<String, Object> generateBreakdownWithRetry(AiWorkflowRun run, Map<String, Object> context, AiWorkflowStepEmitter emitter) {
        try {
            return workflowStepRunner.run(
                    run.getId(),
                    AiWorkflowStep.GENERATE_TASKS,
                    "正在拆解难点...",
                    () -> generateBreakdownByLLM(run, context, emitter),
                    emitter
            );
        } catch (RuntimeException e) {
            return workflowStepRunner.run(
                    run.getId(),
                    AiWorkflowStep.GENERATE_TASKS,
                    "拆解失败，正在重新生成...",
                    () -> generateBreakdownByLLM(run, context, emitter),
                    emitter
            );
        }
    }

    //LLM 拆解难点：输出 {"title","explanation","stages":[{"title":"新增任务点","tasks":[...]}]}
    // stages 单阶段包装是为了复用前端 LEARNING_PLAN 确认面板的渲染结构
    public Map<String, Object> generateBreakdownByLLM(AiWorkflowRun run, Map<String, Object> context, AiWorkflowStepEmitter emitter) {
        try {
            String systemPrompt = """
                    你是学习教练。用户在学习计划某个阶段卡住了，请把这个难点拆解成具体可执行的新任务点，并给出简短讲解。
                    输出纯 JSON，不要任何解释、代码块或前后缀。
                    JSON 格式：
                    {"title":"攻坚标题","explanation":"难点讲解（200 字以内，点明卡点原因与突破方法）","stages":[{"title":"新增任务点","tasks":["任务1","任务2"]}]}
                    约束：
                    1. 任务 3-5 个，按由浅入深排列，具体可执行（不是口号）
                    2. 任务不得与目标阶段已有任务重复，也不互相重复
                    3. 结合用户长期记忆中的背景偏好、站内文章参考
                    4. 事实边界：不编造站内不存在的课程/资料，难度符合用户基础
                    """;

            LlmStreamCaller.LlmStreamResult result = llmStreamCaller.call(
                    "难点拆解失败：",
                    AiWorkflowStep.GENERATE_TASKS,
                    "breakdown",
                    emitter,
                    systemPrompt,
                    buildBreakdownUserPrompt(context),
                    null
            );
            workflowTokenRecorder.accumulate(run, result.usage());
            return parsePlanJson(cleanJson(result.content()));
        } catch (RuntimeException e) {
            throw LlmErrorClassifier.wrap("难点拆解失败：", e);
        } catch (Exception e) {
            throw new RuntimeException("难点拆解失败：" + LlmErrorClassifier.friendlyMessage(e), e);
        }
    }

    //攻坚拆解规则质量检查：任务 3-5 个、标题非空、生成内去重、与目标阶段已有任务不重复
    public Map<String, Object> buildBreakdownQualityCheck(Map<String, Object> plan, List<String> existingTitles) {
        List<String> issues = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();

        List<String> tasks = extractTaskTitles(plan);
        if (tasks.isEmpty()) {
            issues.add("拆解结果没有任务点");
        } else if (tasks.size() < 3 || tasks.size() > 5) {
            issues.add("任务点数量应在 3-5 个，当前 " + tasks.size() + " 个");
        }

        Set<String> existing = existingTitles == null ? new HashSet<>() : new HashSet<>(existingTitles);
        Set<String> seen = new HashSet<>();
        for (String title : tasks) {
            if (title.isBlank()) {
                issues.add("存在空任务标题");
            } else if (!seen.add(title)) {
                issues.add("任务重复：" + title);
            } else if (existing.contains(title)) {
                issues.add("与已有任务重复：" + title);
            }
        }

        Map<String, Object> check = new HashMap<>();
        check.put("passed", issues.isEmpty());
        check.put("issues", issues);
        check.put("suggestions", suggestions);
        return check;
    }

    //LLM 偶尔会多给任务点；攻坚 Workflow 的业务边界是"追加 3-5 个新增任务"，后端做确定性收口。
    public Map<String, Object> normalizeBreakdownPlan(Map<String, Object> plan, List<String> existingTitles) {
        List<String> originalTitles = extractTaskTitles(plan);
        List<String> normalizedTitles = normalizeBreakdownTaskTitles(originalTitles, existingTitles);
        if (normalizedTitles.size() < 3 || normalizedTitles.equals(originalTitles)) {
            return plan;
        }

        Map<String, Object> normalizedPlan = plan == null ? new LinkedHashMap<>() : new LinkedHashMap<>(plan);
        String stageTitle = firstStageTitle(plan);
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("title", workflowContextSupport.isBlank(stageTitle) ? "新增任务点" : stageTitle);
        stage.put("tasks", normalizedTitles);
        normalizedPlan.put("stages", List.of(stage));
        return normalizedPlan;
    }

    private List<String> normalizeBreakdownTaskTitles(List<String> taskTitles, List<String> existingTitles) {
        Set<String> existing = new HashSet<>();
        if (existingTitles != null) {
            for (String title : existingTitles) {
                if (title != null && !title.isBlank()) {
                    existing.add(title.trim());
                }
            }
        }

        Set<String> seen = new HashSet<>();
        List<String> normalized = new ArrayList<>();
        for (String title : taskTitles) {
            if (title == null || title.isBlank()) {
                continue;
            }
            String trimmed = title.trim();
            if (existing.contains(trimmed) || !seen.add(trimmed)) {
                continue;
            }
            normalized.add(trimmed);
            if (normalized.size() == 5) {
                break;
            }
        }
        return normalized;
    }

    private String firstStageTitle(Map<String, Object> plan) {
        if (plan == null || !(plan.get("stages") instanceof List<?> stagesList) || stagesList.isEmpty()) {
            return "";
        }
        Object first = stagesList.get(0);
        if (first instanceof Map<?, ?> stageMap && stageMap.get("title") != null) {
            return String.valueOf(stageMap.get("title"));
        }
        return "";
    }

    //拆解结果（stages 单阶段包装结构）→ 任务标题列表（兼容 List<String> 与 List<Map>）
    public List<String> extractTaskTitles(Map<String, Object> plan) {
        List<String> titles = new ArrayList<>();
        if (plan == null || !(plan.get("stages") instanceof List<?> stagesList)) {
            return titles;
        }
        for (Object s : stagesList) {
            if (!(s instanceof Map<?, ?> stageMap) || !(stageMap.get("tasks") instanceof List<?> tasksList)) {
                continue;
            }
            for (Object t : tasksList) {
                Object titleObj = t instanceof Map<?, ?> m ? m.get("title") : t;
                String title = titleObj == null ? "" : String.valueOf(titleObj);
                if (!title.isBlank()) {
                    titles.add(title);
                }
            }
        }
        return titles;
    }

    //攻坚生成 user prompt：难点诉求 + 目标阶段与已有任务 + 记忆 + RAG + 历史反馈（质量检查/用户意见）
    public String buildBreakdownUserPrompt(Map<String, Object> context) {
        String request = String.valueOf(workflowContextSupport.getMap(context, "input").getOrDefault("request", ""));
        String stageTitle = String.valueOf(context.getOrDefault("targetStageTitle", ""));
        String memoryContext = String.valueOf(context.getOrDefault("memoryContext", ""));
        Object ragObj = workflowContextSupport.getMap(context, "ragContext").get("references");
        String ragSummary = ragObj instanceof List<?> list && !list.isEmpty()
                ? list.size() + " 篇站内相关文章，拆解时可参考其知识结构"
                : "暂无站内相关文章";

        return """
            用户的难点诉求：
            %s

            难点所在阶段：%s

            该阶段已有任务（新任务点不得与它们重复）：
            %s

            用户长期记忆（背景与偏好）：
            %s

            站内知识参考：
            %s
            %s
            请把该难点拆解为新增任务点（纯 JSON，3-5 个任务）。
            """.formatted(
                request,
                workflowContextSupport.isBlank(stageTitle) ? "（未指定）" : stageTitle,
                buildStageExistingTasksSummary(context),
                workflowContextSupport.isBlank(memoryContext) ? "无" : memoryContext,
                ragSummary,
                buildFeedbackSummary(context)
        );
    }

    //feedbackHistory → prompt 段（质量检查反馈 + reject 用户意见）；无历史为空串
    private String buildFeedbackSummary(Map<String, Object> context) {
        Object value = context.get("feedbackHistory");
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("历史反馈（本次生成必须修正这些问题）：");
        for (Object item : list) {
            if (item instanceof Map<?, ?> entry && entry.get("userFeedback") != null) {
                sb.append("\n- ").append(entry.get("userFeedback"));
            }
        }
        return sb.toString();
    }

    //目标阶段已有任务 → 可读列表（prompt 禁止重复用）
    private String buildStageExistingTasksSummary(Map<String, Object> context) {
        Object targetStageId = context.get("targetStageId");
        List<String> titles = new ArrayList<>();
        if (targetStageId instanceof Number stageId && context.get("stageCatalog") instanceof List<?> catalog) {
            for (Object item : catalog) {
                if (!(item instanceof Map<?, ?> stage)) {
                    continue;
                }
                Object id = stage.get("id");
                if (!(id instanceof Number n) || n.longValue() != stageId.longValue()) {
                    continue;
                }
                if (stage.get("tasks") instanceof List<?> tasks) {
                    for (Object t : tasks) {
                        if (t instanceof Map<?, ?> taskMap) {
                            Object titleObj = taskMap.get("title");
                            titles.add(titleObj == null ? "" : String.valueOf(titleObj));
                        }
                    }
                }
                break;
            }
        }
        if (titles.isEmpty()) {
            return "（该阶段暂无任务）";
        }
        return String.join("\n", titles.stream().map(t -> "  - " + t).toList());
    }

    // ==================== 保存 ====================

    //targetPlanId 为 null → 新建（按 source_workflow_run_id 幂等）；非 null → 按 planId 覆盖旧计划
    public Map<String, Object> savePlan(AiWorkflowRun run, Map<String, Object> context) {
        return savePlan(run, context, null);
    }

    public Map<String, Object> savePlan(AiWorkflowRun run, Map<String, Object> context, Long targetPlanId) {
        Map<String, Object> plan = workflowContextSupport.getMap(workflowContextSupport.getStepResults(context), "plan");

        LearningPlans entity = new LearningPlans();
        entity.setId(targetPlanId);
        entity.setUserId(run.getUserId());
        entity.setTitle(String.valueOf(plan.getOrDefault("title", "我的学习计划")));
        entity.setGoal(getGoal(context));
        entity.setStatus(LearningPlans.STATUS_ACTIVE);
        entity.setSourceWorkflowRunId(run.getId());
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        List<LearningStages> stages = new ArrayList<>();
        Object stagesObj = plan.get("stages");
        if (stagesObj instanceof List<?> list) {
            int order = 1;
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> stageMap)) {
                    continue;
                }
                LearningStages stage = new LearningStages();
                stage.setOrderNum(order++);
                Object stageTitle = stageMap.get("title");
                stage.setTitle(stageTitle == null ? "" : String.valueOf(stageTitle));
                stage.setTasks(toTasksJson(stageMap.get("tasks")));
                stage.setCreatedAt(now);
                stage.setUpdatedAt(now);
                stages.add(stage);
            }
        }

        learningPlansService.saveOrUpdatePlan(entity, stages);
        return plan;
    }

    //tasks 列表（List<String> 或 List<Map>）统一转 JSON 字符串；Map 里的 done 状态保留
    public String toTasksJson(Object tasksObj) {
        List<Map<String, Object>> tasks = new ArrayList<>();
        if (tasksObj instanceof List<?> list) {
            for (Object t : list) {
                Map<String, Object> task = new HashMap<>();
                if (t instanceof Map<?, ?> m) {
                    Object taskTitle = m.get("title");
                    task.put("title", taskTitle == null ? "" : String.valueOf(taskTitle));
                    task.put("done", Boolean.TRUE.equals(m.get("done")));
                } else {
                    task.put("title", String.valueOf(t));
                    task.put("done", Boolean.FALSE);
                }
                tasks.add(task);
            }
        }

        try {
            return objectMapper.writeValueAsString(tasks);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    // ==================== JSON / prompt / context 工具 ====================

    //去 ```json 包裹和多余空白
    public String cleanJson(String raw) {
        String cleaned = raw == null ? "" : raw.trim();
        cleaned = cleaned.replace("```json", "").replace("```", "");
        return cleaned.trim();
    }

    public Map<String, Object> parsePlanJson(String raw) {
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("学习计划生成失败：模型返回格式错误");
        }
    }

    //生成 prompt；调整模式（context 含 oldPlan）自动附加当前计划与进度
    public String buildPlanUserPrompt(Map<String, Object> context) {
        String goal = getGoal(context);
        String memoryContext = String.valueOf(context.getOrDefault("memoryContext", ""));
        Object ragObj = workflowContextSupport.getMap(context, "ragContext").get("references");
        String ragSummary = ragObj instanceof List<?> list && !list.isEmpty()
                ? list.size() + " 篇站内相关文章，生成计划时可参考其知识结构"
                : "暂无站内相关文章";

        if (isAdjustMode(context)) {
            Map<String, Object> oldPlan = toMap(context.get("oldPlan"));
            int done = oldDoneCount(oldPlan);
            int total = oldTotalCount(oldPlan);
            String request = String.valueOf(workflowContextSupport.getMap(context, "input").getOrDefault("request", ""));

            return """
                用户学习目标：
                %s

                本次调整诉求：
                %s

                当前计划与进度（%d/%d 项已完成）：
                %s

                用户长期记忆（背景与偏好）：
                %s

                站内知识参考：
                %s

                请基于当前计划与调整诉求生成调整后的完整学习计划（纯 JSON，保留已完成任务）。
                """.formatted(
                    goal,
                    request,
                    done,
                    total,
                    buildOldPlanSummary(oldPlan),
                    workflowContextSupport.isBlank(memoryContext) ? "无" : memoryContext,
                    ragSummary
            );
        }

        return """
            用户学习目标：
            %s

            用户长期记忆（背景与偏好）：
            %s

            站内知识参考：
            %s

            请生成结构化学习计划（纯 JSON）。
            """.formatted(
                goal,
                workflowContextSupport.isBlank(memoryContext) ? "无" : memoryContext,
                ragSummary
        );
    }

    //oldPlan 快照 → 可读文本（阶段 + 任务 + 完成标记）
    private String buildOldPlanSummary(Map<String, Object> oldPlan) {
        StringBuilder sb = new StringBuilder();
        String title = String.valueOf(oldPlan.getOrDefault("title", ""));
        if (!workflowContextSupport.isBlank(title)) {
            sb.append("计划标题：").append(title).append("\n");
        }
        Object stagesObj = oldPlan.get("stages");
        if (stagesObj instanceof List<?> stages) {
            int stageNo = 1;
            for (Object s : stages) {
                if (!(s instanceof Map<?, ?> stageMap)) {
                    continue;
                }
                Object stageTitle = stageMap.get("title");
                sb.append("阶段").append(stageNo++).append("：")
                        .append(stageTitle == null ? "" : stageTitle).append("\n");
                Object tasksObj = stageMap.get("tasks");
                if (tasksObj instanceof List<?> tasks) {
                    for (Object t : tasks) {
                        if (!(t instanceof Map<?, ?> taskMap)) {
                            continue;
                        }
                        Object taskTitle = taskMap.get("title");
                        sb.append("  - ").append(taskTitle == null ? "" : taskTitle);
                        if (Boolean.TRUE.equals(taskMap.get("done"))) {
                            sb.append("（已完成）");
                        }
                        sb.append("\n");
                    }
                }
            }
        }
        return sb.toString().isBlank() ? "（无旧计划数据）" : sb.toString();
    }

    private int oldDoneCount(Map<String, Object> oldPlan) {
        int done = 0;
        Object stagesObj = oldPlan.get("stages");
        if (stagesObj instanceof List<?> stages) {
            for (Object s : stages) {
                if (!(s instanceof Map<?, ?> stageMap) || !(stageMap.get("tasks") instanceof List<?> tasks)) {
                    continue;
                }
                for (Object t : tasks) {
                    if (t instanceof Map<?, ?> taskMap && Boolean.TRUE.equals(taskMap.get("done"))) {
                        done++;
                    }
                }
            }
        }
        return done;
    }

    private int oldTotalCount(Map<String, Object> oldPlan) {
        int total = 0;
        Object stagesObj = oldPlan.get("stages");
        if (stagesObj instanceof List<?> stages) {
            for (Object s : stages) {
                if (s instanceof Map<?, ?> stageMap && stageMap.get("tasks") instanceof List<?> tasks) {
                    total += tasks.size();
                }
            }
        }
        return total;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : new HashMap<>();
    }

    public Map<String, Object> buildInitialContext(String goal) {
        Map<String, Object> context = new HashMap<>();
        context.put("workflowVersion", WORKFLOW_VERSION);
        Map<String, Object> input = new HashMap<>();
        input.put("goal", goal);
        context.put("input", input);
        context.put("memoryContext", "");
        context.put("ragContext", new HashMap<>());
        context.put("stepResults", new HashMap<>());
        context.put("feedbackHistory", new ArrayList<>());
        return context;
    }

    public String getGoal(Map<String, Object> context) {
        return String.valueOf(workflowContextSupport.getMap(context, "input").getOrDefault("goal", ""));
    }

    //剥框架词提取学习主题（RAG 检索关键词、CTA 文案用）
    public String extractGoalTopic(String goal) {
        String topic = goal == null ? "" : goal.trim();
        String before;
        do {
            before = topic;
            topic = topic
                    .replaceFirst("^(我想|我想让|我打算|打算|帮我|请帮我|想|我要)", "")
                    .replaceFirst("^(系统|好好|从头|深入)?(学|学习|入门|进阶|掌握|研究)", "")
                    .replaceFirst("^(一下|一下下)?", "")
                    .trim();
        } while (!topic.equals(before) && !topic.isBlank());
        topic = topic.replaceFirst("(学习路线|学习计划|路线|计划|规划|安排)$", "").trim();
        return topic;
    }

    // ==================== 知识检索 ====================

    public String retrieveMemoryContext(Long userId, String goal) {
        return workflowKnowledgeSupport.retrieveMemoryContext(userId, goal);
    }

    //RAG 检索失败兜底空列表，不阻断流程
    public List<Map<String, Object>> retrieveRagReferences(String goal) {
        return workflowKnowledgeSupport.retrieveRagReferences(goal, extractGoalTopic(goal));
    }
}
