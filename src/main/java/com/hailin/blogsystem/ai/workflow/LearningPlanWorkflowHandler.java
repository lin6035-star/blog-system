package com.hailin.blogsystem.ai.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.LlmErrorClassifier;
import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.ai.rag.ArticleRagSearchService;
import com.hailin.blogsystem.entity.AiEditorCommand;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.LearningStages;
import com.hailin.blogsystem.entity.dto.*;
import com.hailin.blogsystem.service.AiUserMemoryService;
import com.hailin.blogsystem.service.LearningPlansService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;
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
@RequiredArgsConstructor
public class LearningPlanWorkflowHandler implements WorkflowHandler{

    private static final String WORKFLOW_VERSION = "1.0";
    //否定反馈："算了/不用了/不学了..." → 取消 workflow 而不是继续生成
    private static final Pattern NEGATIVE_FEEDBACK = Pattern.compile("(不想学|不学了|不用了|算了|不需要|不是要|不是想)");

    private final ObjectMapper objectMapper;
    private final ArticleRagSearchService articleRagSearchService;
    private final AiUserMemoryService aiUserMemoryService;
    private final ChatClient.Builder chatClientBuilder;
    private final LearningPlansService learningPlansService;
    private final WorkflowStepRunner workflowStepRunner;
    private final WorkflowContextSupport workflowContextSupport;
    private final WorkflowStatusSupport workflowStatusSupport;
    private final WorkflowTokenRecorder workflowTokenRecorder;

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

        Map<String, Object> context = buildInitialContext(goal.trim());

        AiWorkflowRun run = new AiWorkflowRun();
        run.setUserId(userId);
        run.setConversationId(dto == null ? null : dto.getConversationId());
        run.setWorkflowType(AiWorkflowType.LEARNING_PLAN.name());
        run.setWorkflowVersion(WORKFLOW_VERSION);
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
        String goal = getGoal(context);

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
                    () -> retrieveRagReferences(extractGoalTopic(goal)),
                    safeEmitter
            );
            context.put("ragContext", Map.of("references", ragReferences));

            context.put("clarification", buildGoalClarification());
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
                () -> retrieveMemoryContext(run.getUserId(), goal),
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
                () -> retrieveRagReferences(goal),
                safeEmitter
        );
        context.put("ragContext", Map.of("references", ragReferences));
        run.setContextJson(workflowContextSupport.toJson(context));

        //4. GENERATE_PLAN（含质量检查 + auto-retry 一次）
        Map<String, Object> stepResults = workflowContextSupport.getStepResults(context);
        Map<String, Object> plan = workflowStepRunner.run(
                run.getId(),
                AiWorkflowStep.GENERATE_PLAN,
                "正在生成学习计划...",
                () -> generatePlanByLLM(run, context, safeEmitter),
                safeEmitter
        );
        stepResults.put("plan", plan);

        Map<String, Object> check = buildPlanQualityCheck(plan);
        if (Boolean.FALSE.equals(check.get("passed"))) {
            String systemFeedback = buildQualityFeedbackForModel(check);
            workflowContextSupport.appendFeedback(context, AiWorkflowStep.GENERATE_PLAN.name(), AiWorkflowStatus.RUNNING.name(), systemFeedback);

            Map<String, Object> retried = workflowStepRunner.run(
                    run.getId(),
                    AiWorkflowStep.GENERATE_PLAN,
                    "计划未通过检查，正在重新生成...",
                    () -> generatePlanByLLM(run, context, safeEmitter),
                    safeEmitter
            );
            stepResults.put("plan", retried);
            check = buildPlanQualityCheck(retried);
        }
        stepResults.put("qualityCheck", check);

        //停确认（即使最终质量检查没通过也让用户看到当前结果——用户可 reject 给反馈）
        run.setStatus(AiWorkflowStatus.WAITING_LEARNING_PLAN_CONFIRM.name());
        run.setCurrentStep(AiWorkflowStep.GENERATE_PLAN.name());
        run.setContextJson(workflowContextSupport.toJson(context));
        workflowContextSupport.touch(run);

        return AiWorkflowAdvanceResult.of(run);
    }

    // ==================== approve ====================

    //WAITING_LEARNING_PLAN_CONFIRM → SAVE_PLAN → COMPLETED
    @Override
    public AiWorkflowAdvanceResult approve(AiWorkflowRun run, AiWorkflowStepEmitter emitter) {
        AiWorkflowStepEmitter safeEmitter = emitter == null ? AiWorkflowStepEmitter.noop() : emitter;
        AiWorkflowStatus status = workflowStatusSupport.parseStatus(run.getStatus());
        Map<String, Object> context = workflowContextSupport.parseContext(run.getContextJson());

        switch (status) {
            case WAITING_LEARNING_PLAN_CONFIRM -> {
                workflowStepRunner.run(
                        run.getId(),
                        AiWorkflowStep.SAVE_PLAN,
                        "正在保存学习计划...",
                        () -> savePlan(run, context),
                        safeEmitter
                );
                run.setStatus(AiWorkflowStatus.COMPLETED.name());
                run.setCurrentStep(AiWorkflowStep.SAVE_PLAN.name());
                run.setContextJson(workflowContextSupport.toJson(context));
                workflowContextSupport.touch(run);
            }
            default -> throw new IllegalArgumentException("当前状态不允许同意操作");
        }

        return AiWorkflowAdvanceResult.of(run);
    }

    // ==================== reject ====================

    //WAITING_REQUIREMENT_CONFIRM → 否定反馈 → CANCELLED；否则合并补充信息重新生成
    //WAITING_LEARNING_PLAN_CONFIRM → 带意见重新生成计划 → 再确认
    @Override
    public AiWorkflowAdvanceResult reject(AiWorkflowRun run, String feedback, AiWorkflowStepEmitter emitter) {
        AiWorkflowStepEmitter safeEmitter = emitter == null ? AiWorkflowStepEmitter.noop() : emitter;
        String normalizedFeedback = workflowContextSupport.normalizeRequired(feedback, "修改意见不能为空");
        AiWorkflowStatus status = workflowStatusSupport.parseStatus(run.getStatus());
        Map<String, Object> context = workflowContextSupport.parseContext(run.getContextJson());
        workflowContextSupport.appendFeedback(context, run.getCurrentStep(), run.getStatus(), normalizedFeedback);

        switch (status) {
            case WAITING_REQUIREMENT_CONFIRM -> {
                //否定反馈："算了/不用了" → 取消，不继续生成
                if (NEGATIVE_FEEDBACK.matcher(normalizedFeedback).find()) {
                    run.setStatus(AiWorkflowStatus.CANCELLED.name());
                    run.setContextJson(workflowContextSupport.toJson(context));
                    workflowContextSupport.touch(run);
                    return AiWorkflowAdvanceResult.of(run);
                }
                //合并补充信息到 goal（原文 + 用户补充的基础/时长）
                String merged = (getGoal(context) + "，" + normalizedFeedback).trim();
                workflowContextSupport.getMap(context, "input").put("goal", merged);
                //带着完整信息重新跑生成流程（不重新判 goal——用户已经给了更多信息）
                runGenerateFlow(run, merged, context, safeEmitter);
                run.setStatus(AiWorkflowStatus.WAITING_LEARNING_PLAN_CONFIRM.name());
                run.setCurrentStep(AiWorkflowStep.GENERATE_PLAN.name());
            }
            case WAITING_LEARNING_PLAN_CONFIRM -> {
                Map<String, Object> plan = workflowStepRunner.run(
                        run.getId(),
                        AiWorkflowStep.GENERATE_PLAN,
                        "正在按意见重新生成学习计划...",
                        () -> generatePlanByLLM(run, context, safeEmitter),
                        safeEmitter
                );
                workflowContextSupport.getStepResults(context).put("plan", plan);
            }
            default -> throw new IllegalArgumentException("当前状态不允许提交修改意见");
        }

        run.setContextJson(workflowContextSupport.toJson(context));
        workflowContextSupport.touch(run);
        return AiWorkflowAdvanceResult.of(run);
    }

    // ==================== retry ====================

    //初始链路步骤失败 → 重跑初始链路；SAVE_PLAN 失败 → 重跑保存（upsert 幂等，安全）
    @Override
    public AiWorkflowAdvanceResult retry(AiWorkflowRun run, AiWorkflowStepEmitter emitter) {
        AiWorkflowStep step = workflowStatusSupport.parseStep(run.getCurrentStep());

        switch (step) {
            case ANALYZE_GOAL, MEMORY_RETRIEVE, RAG_SEARCH, GENERATE_PLAN -> {
                run.setErrorMessage(null);
                return runInitialSteps(run, emitter);
            }
            case SAVE_PLAN -> {
                Map<String, Object> context = workflowContextSupport.parseContext(run.getContextJson());
                workflowStepRunner.run(
                        run.getId(),
                        AiWorkflowStep.SAVE_PLAN,
                        "正在重新保存学习计划...",
                        () -> savePlan(run, context),
                        emitter == null ? AiWorkflowStepEmitter.noop() : emitter
                );
                run.setStatus(AiWorkflowStatus.COMPLETED.name());
                run.setCurrentStep(AiWorkflowStep.SAVE_PLAN.name());
                run.setContextJson(workflowContextSupport.toJson(context));
                run.setErrorMessage(null);
                workflowContextSupport.touch(run);
            }
            default -> throw new IllegalArgumentException("当前步骤不支持重试: " + step);
        }

        return AiWorkflowAdvanceResult.of(run);
    }

    //第 3 段：生成流程复用 + 保存 + 目标裁判


    // ==================== 步骤实现 ====================

    //reject 补充信息后重跑：MEMORY → RAG → GENERATE_PLAN（和 runInitialSteps 后三段一致，抽出来复用）
    private void runGenerateFlow(AiWorkflowRun run, String goal, Map<String, Object> context, AiWorkflowStepEmitter emitter) {
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
        Map<String, Object> plan = workflowStepRunner.run(
                run.getId(), AiWorkflowStep.GENERATE_PLAN, "正在生成学习计划...",
                () -> generatePlanByLLM(run, context, emitter), emitter);
        workflowContextSupport.getStepResults(context).put("plan", plan);
    }

    //幂等保存：source_workflow_run_id 唯一 + upsert（Service 内实现），重试不产生重复计划
    private Map<String, Object> savePlan(AiWorkflowRun run, Map<String, Object> context) {
        Map<String, Object> plan = workflowContextSupport.getMap(workflowContextSupport.getStepResults(context), "plan");

        LearningPlans entity = new LearningPlans();
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

    //tasks 列表（List<String> 或 List<Map>）统一转 JSON 字符串
    private String toTasksJson(Object tasksObj) {
        List<Map<String, Object>> tasks = new ArrayList<>();
        if (tasksObj instanceof List<?> list) {
            for (Object t : list) {
                Map<String, Object> task = new HashMap<>();
                if (t instanceof Map<?, ?> m) {
                    Object taskTitle = m.get("title");
                    task.put("title", taskTitle == null ? "" : String.valueOf(taskTitle));
                    task.put("done", Boolean.FALSE);
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
        topic = extractGoalTopic(topic);
        if (topic.isBlank() || topic.length() < 2) {
            return true;
        }
        return topic.contains("啥") || topic.contains("点东西") || topic.contains("随便");
    }

    //剥框架词提取学习主题（RAG 检索关键词、CTA 文案用）
    private String extractGoalTopic(String goal) {
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

    //不再是"判断用户是否要规划"（那要全覆盖），而是"识别明确到可以跳过确认的表达"。
    //识别不到 → 默认追问（多一轮对话，可接受）；只有高置信强信号才直接生成。
    //防线是追问确认（human-in-the-loop），不是这条规则——规则漏了最多多问一句，不会误生成。
    private boolean hasPlanRequest(String text) {
        return text.matches(".*(学习路线|学习计划|学习规划).*")
                || text.matches(".*(帮我|给我|制定|安排).{0,8}(规划|路线|计划|学习).*");
    }

    private Map<String, Object> buildGoalClarification() {
        Map<String, Object> clarification = new HashMap<>();
        clarification.put("required", true);
        clarification.put("question", "你的基础怎么样？计划学多久？");
        return clarification;
    }


    //第 4 段：LLM 生成 + 质量检查 + 辅助


    //LLM 生成结构化计划 JSON：{"title":"...","stages":[{"title":"...","tasks":["任务1","任务2"]}]}
    private Map<String, Object> generatePlanByLLM(AiWorkflowRun run, Map<String, Object> context, AiWorkflowStepEmitter emitter) {
        try {
            StringBuilder json = new StringBuilder();
            TokenUsageAccumulator usage = new TokenUsageAccumulator();

            chatClientBuilder.build()
                    .prompt()
                    .options(OpenAiChatOptions.builder().streamUsage(true).build())
                    .system("""
                        你是学习规划专家。输出纯 JSON，不要任何解释、代码块或前后缀。
                        JSON 格式：
                        {"title":"计划标题","stages":[{"title":"阶段标题","tasks":["任务1","任务2"]}]}
                        约束：
                        1. 阶段 3-8 个，按学习顺序排列
                        2. 每阶段任务 2-8 个，任务要具体可执行（不是口号）
                        3. 结合用户目标、长期记忆中的背景偏好、站内文章参考
                        4. 事实边界：不编造站内不存在的课程/资料，任务难度符合用户基础
                        """)
                    .user(buildPlanUserPrompt(context))
                    .stream()
                    .chatResponse()
                    .timeout(Duration.ofSeconds(60))
                    .doOnNext(response -> {
                        String chunk = response.getResult() == null ? "" : response.getResult().getOutput().getText();
                        if (chunk != null && !chunk.isEmpty()) {
                            json.append(chunk);
                            emitter.emitContent(AiWorkflowStep.GENERATE_PLAN.name(), "plan", chunk);
                        }
                        usage.add(response.getMetadata().getUsage());
                    })
                    .blockLast();

            workflowTokenRecorder.accumulate(run, usage);

            if (json.toString().isBlank()) {
                throw new RuntimeException("学习计划生成失败：模型返回空内容");
            }
            return parsePlanJson(cleanJson(json.toString()));
        } catch (RuntimeException e) {
            throw LlmErrorClassifier.wrap("学习计划生成失败：", e);
        } catch (Exception e) {
            throw new RuntimeException("学习计划生成失败：" + LlmErrorClassifier.friendlyMessage(e), e);
        }
    }

    private Map<String, Object> parsePlanJson(String raw) {
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("学习计划生成失败：模型返回格式错误");
        }
    }

    //去 ```json 包裹和多余空白
    private String cleanJson(String raw) {
        String cleaned = raw == null ? "" : raw.trim();
        cleaned = cleaned.replace("```json", "").replace("```", "");
        return cleaned.trim();
    }

    //规则质量检查：阶段数、任务数、非空、全局去重
    private Map<String, Object> buildPlanQualityCheck(Map<String, Object> plan) {
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

    //检查问题 → 模型反馈文本（auto-retry 用）
    private String buildQualityFeedbackForModel(Map<String, Object> qualityCheck) {
        StringBuilder feedback = new StringBuilder("系统质量检查未通过，请重新生成学习计划：\n");
        Object issues = qualityCheck.get("issues");
        if (issues instanceof List<?> list) {
            for (Object issue : list) {
                feedback.append("- ").append(issue).append("\n");
            }
        }
        feedback.append("要求：阶段 3-8 个，每阶段任务 2-8 个，任务具体可执行，无重复。只输出 JSON。");
        return feedback.toString();
    }

    // ==================== 记忆 / RAG ====================

    private String retrieveMemoryContext(Long userId, String goal) {
        if (userId == null) {
            return "";
        }
        try {
            String memoryPrompt = aiUserMemoryService.buildMemoryPrompt(userId, goal);
            return memoryPrompt == null ? "" : memoryPrompt;
        } catch (Exception e) {
            return "";
        }
    }

    //RAG 检索失败兜底空列表，不阻断流程
    private List<Map<String, Object>> retrieveRagReferences(String goal) {
        try {
            AiIntent intent = new AiIntent();
            intent.setIntent("ARTICLE_SEARCH");
            intent.setKeyWord(goal);
            var result = articleRagSearchService.search(goal, intent);
            if (result == null || result.contexts() == null || result.contexts().isEmpty()) {
                return new ArrayList<>();
            }
            return result.contexts().stream().map(this::toRagReference).toList();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private Map<String, Object> toRagReference(ArticleRagContext context) {
        Map<String, Object> reference = new HashMap<>();
        reference.put("title", context.title());
        reference.put("snippet", context.content());
        return reference;
    }

    // ==================== context / prompt 工具 ====================

    private Map<String, Object> buildInitialContext(String goal) {
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

    private String getGoal(Map<String, Object> context) {
        return String.valueOf(workflowContextSupport.getMap(context, "input").getOrDefault("goal", ""));
    }

    private String buildPlanUserPrompt(Map<String, Object> context) {
        String goal = getGoal(context);
        String memoryContext = String.valueOf(context.getOrDefault("memoryContext", ""));
        Object ragObj = workflowContextSupport.getMap(context, "ragContext").get("references");
        String ragSummary = ragObj instanceof List<?> list && !list.isEmpty()
                ? list.size() + " 篇站内相关文章，生成计划时可参考其知识结构"
                : "暂无站内相关文章";

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

}
