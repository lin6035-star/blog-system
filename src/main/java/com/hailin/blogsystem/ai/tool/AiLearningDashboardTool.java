package com.hailin.blogsystem.ai.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.memory.AiEpisodicMemoryRetrieveService;
import com.hailin.blogsystem.ai.memory.AiMemoryRetrieveService;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.AiSessions;
import com.hailin.blogsystem.entity.AiUserMemories;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.dto.EpisodicMemoryRagContext;
import com.hailin.blogsystem.entity.dto.MemoryRagContext;
import com.hailin.blogsystem.entity.vo.LearningPlansDetailVO;
import com.hailin.blogsystem.mapper.AiSessionMapper;
import com.hailin.blogsystem.service.AiUserMemoryService;
import com.hailin.blogsystem.service.LearningPlansService;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class AiLearningDashboardTool implements ToolCallback {

    private static final String SCHEMA = """
            {"type":"object","properties":{"question":{"type":"string"}},"required":[]}""";

    private final LearningPlansService learningPlansService;
    private final AiMemoryRetrieveService aiMemoryRetrieveService;
    private final AiEpisodicMemoryRetrieveService aiEpisodicMemoryRetrieveService;
    private final AiUserMemoryService aiUserMemoryService;
    private final AiSessionMapper aiSessionMapper;
    private final BlogAiProperties blogAiProperties;
    private final ObjectMapper objectMapper;

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("getLearningDashboard")
                .description("查询当前用户学习计划总览、当前阶段提示和相关记忆摘要。只返回决策所需的轻量数据。")
                .inputSchema(SCHEMA)
                .build();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        Long userId = extractUserId(toolContext);

        if (userId == null) {
            return """
                    {
                      "schemaVersion": 1,
                      "emptyState": "NOT_LOGIN",
                      "emptyHint": "当前未登录，无法查询学习计划。"
                    }
                    """;
        }

        try {
            String question = parseQuestion(toolInput);

            List<LearningPlans> plans = learningPlansService.listByUser(userId);
            List<LearningPlans> activePlans = plans.stream()
                    .filter(plan -> LearningPlans.STATUS_ACTIVE.equals(plan.getStatus()))
                    .toList();

            Long sessionId = extractSessionId(toolContext);
            boolean hasActiveWorkflow = hasActiveWorkflow(userId, sessionId);

            ActivePlanData activePlanData =
                    buildActivePlanData(activePlans, userId);

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("totalPlanCount", plans.size());
            summary.put("activePlanCount", activePlans.size());
            summary.put("hasActiveWorkflow", hasActiveWorkflow);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schemaVersion", 1);
            result.put("question", question);
            result.put("generatedAt", LocalDateTime.now().toString());
            result.put("summary", summary);

            result.put("emptyState", resolveEmptyState(plans, activePlans));
            result.put("emptyHint", resolveEmptyHint(plans, activePlans));

            result.put(
                    "planSummaries",
                    buildPlanSummaries(plans, userId)
            );

            result.put(
                    "activePlanSnapshot",
                    buildActivePlanSnapshot(activePlanData)
            );

            result.put(
                    "memorySnippets",
                    buildMemorySnippets(userId, question)
            );

            result.put(
                    "cappedHints",
                    buildCappedHints(activePlanData)
            );

            return objectMapper.writeValueAsString(result);

        } catch (Exception e) {
            log.warn("学习 Dashboard 生成失败，userId={}", userId, e);
            return errorJson();
        }
    }

    /**
     * 生成计划概要。
     *
     * 每个计划只返回：
     * - id
     * - 标题
     * - 状态
     * - 进度百分比
     * - 当前阶段标题
     *
     * 不返回完整任务内容，避免一次工具调用产生大量 token。
     */
    private List<Map<String, Object>> buildPlanSummaries(
            List<LearningPlans> plans,
            Long userId
    ) {
        List<Map<String, Object>> summaries = new ArrayList<>();

        for (LearningPlans plan : plans) {
            LearningPlansDetailVO detail = safeGetDetail(plan.getId(), userId);

            LearningPlansDetailVO.StageProgress currentStage =
                    detail == null
                            ? null
                            : findCurrentStage(detail.getStages());

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("planId", plan.getId());
            summary.put(
                    "title",
                    limit(
                            plan.getTitle(),
                            blogAiProperties.getAgent()
                                    .getDashboard()
                                    .getTitleMaxLength()
                    )
            );
            summary.put("status", plan.getStatus());
            summary.put(
                    "progressPercent",
                    calculateProgressPercent(detail)
            );
            summary.put(
                    "currentStageTitle",
                    currentStage == null
                            ? ""
                            : limit(
                                    currentStage.getTitle(),
                                    blogAiProperties.getAgent()
                                            .getDashboard()
                                            .getTitleMaxLength()
                            )
            );

            summaries.add(summary);
        }

        return summaries;
    }

    /**
     * 构造当前活跃计划的数据。
     *
     * 多个 ACTIVE 计划时，仍然取最新的一个作为 snapshot，
     * 但 emptyState 会返回 MULTIPLE_ACTIVE，提醒模型不要擅自修改计划。
     */
    private ActivePlanData buildActivePlanData(
            List<LearningPlans> activePlans,
            Long userId
    ) {
        if (activePlans.isEmpty()) {
            return null;
        }

        // listByUser 当前按 createdAt 倒序，第一条就是最新活跃计划。
        LearningPlans activePlan = activePlans.get(0);

        LearningPlansDetailVO detail =
                safeGetDetail(activePlan.getId(), userId);

        LearningPlansDetailVO.StageProgress currentStage =
                detail == null
                        ? null
                        : findCurrentStage(detail.getStages());

        return new ActivePlanData(activePlan, detail, currentStage);
    }

    /**
     * 当前阶段只返回轻量任务预览。
     */
    private Map<String, Object> buildActivePlanSnapshot(
            ActivePlanData activePlanData
    ) {
        if (activePlanData == null) {
            return null;
        }

        LearningPlans plan = activePlanData.plan();
        LearningPlansDetailVO detail = activePlanData.detail();
        LearningPlansDetailVO.StageProgress currentStage =
                activePlanData.currentStage();

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("planId", plan.getId());
        snapshot.put(
                "title",
                limit(
                        plan.getTitle(),
                        blogAiProperties.getAgent()
                                .getDashboard()
                                .getTitleMaxLength()
                )
        );
        snapshot.put("status", plan.getStatus());
        snapshot.put("progressPercent", calculateProgressPercent(detail));

        if (currentStage == null) {
            snapshot.put("currentStage", null);
            return snapshot;
        }

        Map<String, Object> stageSnapshot = new LinkedHashMap<>();
        stageSnapshot.put("stageId", currentStage.getId());
        stageSnapshot.put(
                "title",
                limit(
                        currentStage.getTitle(),
                        blogAiProperties.getAgent()
                                .getDashboard()
                                .getTitleMaxLength()
                )
        );

        List<Map<String, Object>> taskPreviews = new ArrayList<>();

        List<LearningPlansDetailVO.TaskItem> tasks =
                currentStage.getTasks() == null
                        ? List.of()
                        : currentStage.getTasks();

        for (LearningPlansDetailVO.TaskItem task : tasks) {
            Map<String, Object> taskPreview = new LinkedHashMap<>();
            taskPreview.put(
                    "title",
                    limit(
                            task.getTitle(),
                            blogAiProperties.getAgent()
                                    .getDashboard()
                                    .getTaskTitleMaxLength()
                    )
            );
            taskPreview.put("done", task.isDone());
            taskPreviews.add(taskPreview);
        }

        stageSnapshot.put("tasks", taskPreviews);
        snapshot.put("currentStage", stageSnapshot);

        return snapshot;
    }

    /**
     * cappedHints 只返回当前阶段未完成任务的前 N 条。
     *
     * 这里返回真实任务标题，不让模型自己编造任务。
     */
    private List<String> buildCappedHints(ActivePlanData activePlanData) {
        if (activePlanData == null
                || activePlanData.currentStage() == null) {
            return List.of();
        }

        int limit = blogAiProperties.getAgent()
                .getDashboard()
                .getHintLimit();

        int titleMaxLength = blogAiProperties.getAgent()
                .getDashboard()
                .getTaskTitleMaxLength();

        List<String> hints = new ArrayList<>();

        for (LearningPlansDetailVO.TaskItem task :
                activePlanData.currentStage().getTasks()) {

            if (task.isDone()) {
                continue;
            }

            String title = limit(task.getTitle(), titleMaxLength);

            if (title.isBlank()) {
                continue;
            }

            hints.add(title);

            if (hints.size() >= limit) {
                break;
            }
        }

        return hints;
    }

    /**
     * 记忆摘要：
     *
     * question 非空：
     * - 语义记忆向量召回
     * - 情景记忆向量召回
     *
     * question 为空：
     * - 使用已有的高质量语义记忆列表
     *
     * 注意：不要在 Tool 线程里调用 listCurrentUserMemories()，
     * 因为那个方法依赖 UserContext，而 Tool 线程不一定有 ThreadLocal。
     */
    private List<Map<String, Object>> buildMemorySnippets(
            Long userId,
            String question
    ) {
        int limit = blogAiProperties.getAgent()
                .getDashboard()
                .getMemoryLimit();

        int maxLength = blogAiProperties.getAgent()
                .getDashboard()
                .getMemoryMaxLength();

        List<Map<String, Object>> snippets = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        if (question == null || question.isBlank()) {
            try {
                List<AiUserMemories> memories =
                        aiUserMemoryService.listPromptMemories(userId);

                for (AiUserMemories memory : memories) {
                    addSnippet(
                            snippets,
                            seen,
                            "SEMANTIC",
                            memory.getId(),
                            memory.getMemoryType(),
                            memory.getMemoryKey(),
                            memory.getContent(),
                            null,
                            maxLength,
                            limit
                    );

                    if (snippets.size() >= limit) {
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("读取默认语义记忆失败，userId={}", userId, e);
            }

            return snippets;
        }

        // 语义记忆召回失败时不能影响学习 Dashboard。
        try {
            List<MemoryRagContext> memories =
                    aiMemoryRetrieveService.retrieve(userId, question);

            for (MemoryRagContext memory : memories) {
                addSnippet(
                        snippets,
                        seen,
                        "SEMANTIC",
                        memory.memoryId(),
                        memory.memoryType(),
                        memory.memoryKey(),
                        memory.content(),
                        null,
                        maxLength,
                        limit
                );

                if (snippets.size() >= limit) {
                    return snippets;
                }
            }
        } catch (Exception e) {
            log.warn("语义记忆召回失败，userId={}", userId, e);
        }

        // 再用情景记忆补足剩余数量。
        try {
            String projectKey = blogAiProperties.getProjectKey();

            List<EpisodicMemoryRagContext> memories =
                    aiEpisodicMemoryRetrieveService.retrieveForPrompt(
                            userId,
                            projectKey,
                            question
                    );

            for (EpisodicMemoryRagContext memory : memories) {
                addSnippet(
                        snippets,
                        seen,
                        "EPISODIC",
                        memory.memoryId(),
                        memory.memoryType(),
                        memory.title(),
                        memory.content(),
                        memory.occurredAt(),
                        maxLength,
                        limit
                );

                if (snippets.size() >= limit) {
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("情景记忆召回失败，userId={}", userId, e);
        }

        return snippets;
    }

    /**
     * 统一记忆摘要格式，避免把完整 Memory 实体直接暴露给模型。
     */
    private void addSnippet(
            List<Map<String, Object>> snippets,
            Set<String> seen,
            String memoryLayer,
            Long memoryId,
            String memoryType,
            String memoryKey,
            String content,
            LocalDateTime occurredAt,
            int maxLength,
            int limit
    ) {
        if (content == null || content.isBlank()) {
            return;
        }

        String normalizedContent = content.trim();
        String dedupeKey = memoryLayer + ":" + normalizedContent;

        if (!seen.add(dedupeKey)) {
            return;
        }

        Map<String, Object> snippet = new LinkedHashMap<>();
        snippet.put("memoryLayer", memoryLayer);
        snippet.put("memoryId", memoryId);
        snippet.put("memoryType", memoryType);
        snippet.put("memoryKey", memoryKey);
        snippet.put("content", limit(normalizedContent, maxLength));

        snippets.add(snippet);

        if (snippets.size() > limit) {
            snippets.remove(snippets.size() - 1);
        }
    }

    /**
     * 当前阶段：
     * - 优先找第一个存在未完成任务的阶段
     * - 如果所有阶段都没有未完成任务，则取第一个阶段
     */
    private LearningPlansDetailVO.StageProgress findCurrentStage(
            List<LearningPlansDetailVO.StageProgress> stages
    ) {
        if (stages == null || stages.isEmpty()) {
            return null;
        }

        for (LearningPlansDetailVO.StageProgress stage : stages) {
            List<LearningPlansDetailVO.TaskItem> tasks = stage.getTasks();

            if (tasks == null) {
                continue;
            }

            boolean hasPendingTask = tasks.stream()
                    .anyMatch(task -> !task.isDone());

            if (hasPendingTask) {
                return stage;
            }
        }

        return stages.get(0);
    }

    private int calculateProgressPercent(LearningPlansDetailVO detail) {
        if (detail == null || detail.getTotalTasks() <= 0) {
            return 0;
        }

        return (int) Math.round(
                detail.getDoneTasks() * 100.0 / detail.getTotalTasks()
        );
    }

    private LearningPlansDetailVO safeGetDetail(
            Long planId,
            Long userId
    ) {
        try {
            return learningPlansService.getDetail(planId, userId);
        } catch (Exception e) {
            log.warn(
                    "读取学习计划详情失败，planId={}, userId={}",
                    planId,
                    userId,
                    e
            );
            return null;
        }
    }

    private boolean hasActiveWorkflow(Long userId, Long sessionId) {
        if (userId == null || sessionId == null) {
            return false;
        }

        AiSessions session = aiSessionMapper.selectOne(
                new LambdaQueryWrapper<AiSessions>()
                        .eq(AiSessions::getId, sessionId)
                        .eq(AiSessions::getUserId, userId)
        );

        return session != null
                && session.getActiveWorkflowRunId() != null;
    }

    private String resolveEmptyState(
            List<LearningPlans> plans,
            List<LearningPlans> activePlans
    ) {
        if (plans.isEmpty()) {
            return "NO_PLANS";
        }

        if (activePlans.isEmpty()) {
            return "NO_ACTIVE_PLAN";
        }

        if (activePlans.size() > 1) {
            return "MULTIPLE_ACTIVE";
        }

        return "OK";
    }

    private String resolveEmptyHint(
            List<LearningPlans> plans,
            List<LearningPlans> activePlans
    ) {
        if (plans.isEmpty()) {
            return "当前还没有学习计划。";
        }

        if (activePlans.isEmpty()) {
            return "当前没有进行中的学习计划。";
        }

        if (activePlans.size() > 1) {
            return "当前存在多个进行中的学习计划，需要先确认目标计划。";
        }

        return "已返回轻量学习面板。";
    }

    private String parseQuestion(String toolInput) {
        if (toolInput == null
                || toolInput.isBlank()
                || "{}".equals(toolInput.trim())) {
            return "";
        }

        try {
            String question = objectMapper
                    .readTree(toolInput)
                    .path("question")
                    .asText("");

            return question == null ? "" : question.trim();

        } catch (Exception e) {
            // 兼容手写 ToolCallback.call("今天学什么")
            return toolInput.trim();
        }
    }

    private Long extractUserId(ToolContext toolContext) {
        Object value = toolContext == null
                ? null
                : toolContext.getContext().get("userId");

        if (value instanceof Number number) {
            return number.longValue();
        }

        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.valueOf(text);
            } catch (NumberFormatException ignored) {
                // 继续使用 UserContext 兜底
            }
        }

        return UserContext.get();
    }

    private Long extractSessionId(ToolContext toolContext) {
        Object value = toolContext == null
                ? null
                : toolContext.getContext().get("sessionId");

        if (value instanceof Number number) {
            return number.longValue();
        }

        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.valueOf(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        return null;
    }

    private String limit(String text, int max) {
        if (text == null || text.isBlank()) {
            return "";
        }

        if (max <= 0 || text.length() <= max) {
            return text;
        }

        return text.substring(0, max);
    }

    private String errorJson() {
        return """
                {
                  "schemaVersion": 1,
                  "emptyState": "ERROR",
                  "emptyHint": "学习面板生成失败。"
                }
                """;
    }

    private record ActivePlanData(
            LearningPlans plan,
            LearningPlansDetailVO detail,
            LearningPlansDetailVO.StageProgress currentStage
    ) {
    }
}
