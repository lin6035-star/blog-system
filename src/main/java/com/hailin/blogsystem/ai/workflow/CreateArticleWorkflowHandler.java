package com.hailin.blogsystem.ai.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.LlmErrorClassifier;
import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.ai.rag.ArticleRagSearchService;
import com.hailin.blogsystem.entity.AiEditorCommand;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import com.hailin.blogsystem.entity.dto.ArticleRagSearchResult;
import com.hailin.blogsystem.entity.dto.AiWorkflowCreateArticleDTO;
import com.hailin.blogsystem.entity.dto.AiWorkflowStatus;
import com.hailin.blogsystem.entity.dto.AiWorkflowStep;
import com.hailin.blogsystem.entity.dto.AiWorkflowType;
import com.hailin.blogsystem.service.AiUserMemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*创建 → WAITING_REQUIREMENT_CONFIRM  (需求不明确)
     → WAITING_OUTLINE_CONFIRM      (大纲已生成)
     → WAITING_DRAFT_CONFIRM        (草稿+质量检查已生成)
     → WAITING_FILL_CONFIRM         (草稿已确认，等用户确认填充)
     → WAITING_USER_SAVE            (已填充编辑器)
     → COMPLETED
每个等待态都可以 approve（同意进入下一阶段）或 reject（不同意，拿着意见重做当前阶段）
*/

@Component
@RequiredArgsConstructor
public class CreateArticleWorkflowHandler implements WorkflowHandler {

    private static final String WORKFLOW_VERSION = "1.0";

    @Override
    public String workflowType() {
        return AiWorkflowType.CREATE_ARTICLE.name();
    }

    private final ObjectMapper objectMapper;
    private final ArticleRagSearchService articleRagSearchService;
    private final AiUserMemoryService aiUserMemoryService;
    private final ChatClient.Builder chatClientBuilder;
    private final WorkflowStepRunner workflowStepRunner;
    private final WorkflowContextSupport workflowContextSupport;
    private final WorkflowStatusSupport workflowStatusSupport;
    private final WorkflowTokenRecorder workflowTokenRecorder;

    //创建工作流
    public AiWorkflowAdvanceResult create(Long userId, AiWorkflowCreateArticleDTO dto) {
        return create(userId, dto, AiWorkflowStepEmitter.noop());
    }

    public AiWorkflowAdvanceResult create(Long userId, AiWorkflowCreateArticleDTO dto, AiWorkflowStepEmitter emitter) {
        String requirement = workflowContextSupport.normalizeRequired(//normalizeRequire 非空校验
                dto == null ? null : dto.getRequirement(),
                "写作需求不能为空"
        );

        Long conversationId = dto == null ? null : dto.getConversationId();
        LocalDateTime now = LocalDateTime.now();

        //create 只初始化 run（不执行 LLM），初始步骤由 runInitialSteps 在 Service save 之后执行
        //这样 WorkflowStepRunner 落步骤日志时 run 已入库（有 id），不需要再补记近似耗时日志
        Map<String, Object> context = buildInitialContext(requirement);

        AiWorkflowRun run = new AiWorkflowRun();
        run.setUserId(userId);
        run.setConversationId(conversationId);
        run.setWorkflowType(AiWorkflowType.CREATE_ARTICLE.name());
        run.setWorkflowVersion(WORKFLOW_VERSION);

        if (isRequirementUnclear(requirement)) {  //isRequirementUnclear(requirement)判断需求是否模糊
            // 生成"你想写哪个主题？"提示文字
            context.put("clarification", buildRequirementClarification());

            run.setStatus(AiWorkflowStatus.WAITING_REQUIREMENT_CONFIRM.name());
            run.setCurrentStep(AiWorkflowStep.REQUIREMENT_ANALYZE.name());
        } else {
            run.setStatus(AiWorkflowStatus.RUNNING.name());
            run.setCurrentStep(AiWorkflowStep.REQUIREMENT_ANALYZE.name());
        }

        run.setContextJson(workflowContextSupport.toJson(context));
        run.setRetryCount(0);
        run.setInputTokens(0);
        run.setOutputTokens(0);
        run.setTotalTokens(0);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);

        return AiWorkflowAdvanceResult.of(run);
    }

    /**
     * 执行初始步骤（需求分析 → 记忆召回 → RAG 检索 → 生成大纲）。
     * 必须在 Service save(run) 之后调用，否则 WorkflowStepRunner 无法落步骤日志。
     */
    public AiWorkflowAdvanceResult runInitialSteps(AiWorkflowRun run, AiWorkflowStepEmitter emitter) {
        AiWorkflowStepEmitter safeEmitter = emitter == null ? AiWorkflowStepEmitter.noop() : emitter;
        Map<String, Object> context = workflowContextSupport.parseContext(run.getContextJson());

        Map<String, Object> requirementMap = workflowContextSupport.getMap(context, "requirement");
        String requirement = String.valueOf(requirementMap.getOrDefault("rawRequirement", ""));

        run.setCurrentStep(AiWorkflowStep.REQUIREMENT_ANALYZE.name());

        Boolean clear = workflowStepRunner.run(
                run.getId(),
                AiWorkflowStep.REQUIREMENT_ANALYZE,
                "正在分析写作需求...",
                () -> !isRequirementUnclear(requirement),
                safeEmitter
        );

        if (!Boolean.TRUE.equals(clear)) {
            context.put("clarification", buildRequirementClarification());
            run.setStatus(AiWorkflowStatus.WAITING_REQUIREMENT_CONFIRM.name());
            run.setCurrentStep(AiWorkflowStep.REQUIREMENT_ANALYZE.name());
            run.setContextJson(workflowContextSupport.toJson(context));
            workflowContextSupport.touch(run);
            return AiWorkflowAdvanceResult.of(run);
        }

        run.setCurrentStep(AiWorkflowStep.MEMORY_RETRIEVE.name());
        String memoryContext = workflowStepRunner.run(
                run.getId(),
                AiWorkflowStep.MEMORY_RETRIEVE,
                "正在读取写作偏好...",
                () -> retrieveMemoryContext(run.getUserId(), requirement),
                safeEmitter
        );
        context.put("memoryContext", memoryContext);
        run.setContextJson(workflowContextSupport.toJson(context));

        run.setCurrentStep(AiWorkflowStep.RAG_SEARCH.name());
        List<Map<String, Object>> ragReferences = workflowStepRunner.run(
                run.getId(),
                AiWorkflowStep.RAG_SEARCH,
                "正在检索站内相关文章...",
                () -> retrieveRagReferences(requirement),
                safeEmitter
        );
        context.put("ragReferences", ragReferences);
        run.setContextJson(workflowContextSupport.toJson(context));

        run.setCurrentStep(AiWorkflowStep.GENERATE_OUTLINE.name());
        String outline = workflowStepRunner.run(
                run.getId(),
                AiWorkflowStep.GENERATE_OUTLINE,
                "正在生成文章大纲...",
                () -> buildOutlineByLLM(run, requirement, memoryContext, ragReferences, safeEmitter),
                safeEmitter
        );
        context.put("outline", outline);

        run.setStatus(AiWorkflowStatus.WAITING_OUTLINE_CONFIRM.name());
        run.setCurrentStep(AiWorkflowStep.GENERATE_OUTLINE.name());
        run.setContextJson(workflowContextSupport.toJson(context));
        workflowContextSupport.touch(run);

        return AiWorkflowAdvanceResult.of(run);
    }


    //同意当前阶段，推进
    public AiWorkflowAdvanceResult approve(AiWorkflowRun run){
        return approve(run, AiWorkflowStepEmitter.noop());
    }
    @Override
    public AiWorkflowAdvanceResult approve(AiWorkflowRun run,AiWorkflowStepEmitter emitter) {
        AiWorkflowStatus status = workflowStatusSupport.parseStatus(run.getStatus());
        Map<String, Object> context = workflowContextSupport.parseContext(run.getContextJson());

        //遇到这个状态直接拦住，需求不明确不能直接同意
        if (status == AiWorkflowStatus.WAITING_REQUIREMENT_CONFIRM) {
            throw new IllegalArgumentException("请先补充写作主题");
        }
        //status是大纲已生成，等待用户反馈
        if (status == AiWorkflowStatus.WAITING_OUTLINE_CONFIRM) {
            context.put("draft", workflowStepRunner.run(run.getId(),
                    AiWorkflowStep.GENERATE_DRAFT,
                    "正在生成正文草稿...",
                    () -> buildDraft(run, context, emitter),
                    emitter
            ));

            Map<String, Object> qualityCheck = workflowStepRunner.run(run.getId(),
                    AiWorkflowStep.QUALITY_CHECK,
                    "正在执行质量检查...",
                    () -> buildQualityCheck(context),
                    emitter
            );
            context.put("qualityCheck", qualityCheck);

            // 第一次不合格 → 把检查问题喂回模型，自动重写一次
            if (Boolean.FALSE.equals(qualityCheck.get("passed"))) {
                String systemFeedback = buildQualityFeedbackForModel(qualityCheck);

                workflowContextSupport.appendFeedback(
                        context,
                        AiWorkflowStep.QUALITY_CHECK.name(),
                        AiWorkflowStatus.RUNNING.name(),
                        systemFeedback
                );

                context.put("draft", workflowStepRunner.run(run.getId(),
                        AiWorkflowStep.GENERATE_DRAFT,
                        "草稿未通过质量检查，正在重新生成正文...",
                        () -> rebuildDraft(run, context, systemFeedback, emitter),
                        emitter
                ));

                qualityCheck = workflowStepRunner.run(run.getId(),
                        AiWorkflowStep.QUALITY_CHECK,
                        "正在重新执行质量检查...",
                        () -> buildQualityCheck(context),
                        emitter
                );
                context.put("qualityCheck", qualityCheck);
            }

            run.setStatus(AiWorkflowStatus.WAITING_DRAFT_CONFIRM.name());
            run.setCurrentStep(AiWorkflowStep.QUALITY_CHECK.name());
            run.setContextJson(workflowContextSupport.toJson(context));
            workflowContextSupport.touch(run);

            return AiWorkflowAdvanceResult.of(run);
        }

        //status停在草稿确认，等待用户反馈同意该阶段或者不同意
        if (status == AiWorkflowStatus.WAITING_DRAFT_CONFIRM) {
            Map<String, Object> qualityCheck = workflowContextSupport.getMap(context, "qualityCheck");
            if (Boolean.FALSE.equals(qualityCheck.get("passed"))) {
                throw new IllegalArgumentException("草稿质量检查未通过，请根据检查问题提交修改意见");
            }

            //状态进入下一步，准备填充
            run.setStatus(AiWorkflowStatus.WAITING_FILL_CONFIRM.name());
            run.setCurrentStep(AiWorkflowStep.FILL_ARTICLE.name());
            workflowContextSupport.touch(run);

            return AiWorkflowAdvanceResult.of(run);
        }

        if (status == AiWorkflowStatus.WAITING_FILL_CONFIRM) {
            //从 draft 构建 fillArticle 指令
            AiEditorCommand editorAction = buildEditorAction(context);

            run.setStatus(AiWorkflowStatus.WAITING_USER_SAVE.name());//前端收到 editorAction 填充编辑器
            run.setCurrentStep(AiWorkflowStep.FILL_ARTICLE.name());
            workflowContextSupport.touch(run);

            return AiWorkflowAdvanceResult.withEditorAction(run, editorAction);
        }

        throw new IllegalArgumentException("当前状态不允许同意操作");
    }

    //不同意该阶段的方案，重新生成
    @Override
    public AiWorkflowAdvanceResult reject(AiWorkflowRun run, String feedback,AiWorkflowStepEmitter emitter) {
        String normalizedFeedback = workflowContextSupport.normalizeRequired(feedback, "修改意见不能为空");
        AiWorkflowStatus status = workflowStatusSupport.parseStatus(run.getStatus());

        Map<String, Object> context = workflowContextSupport.parseContext(run.getContextJson());
        workflowContextSupport.appendFeedback(context, run.getCurrentStep(), run.getStatus(), normalizedFeedback);

        if (status == AiWorkflowStatus.WAITING_REQUIREMENT_CONFIRM) {
            Map<String, Object> requirementMap = workflowContextSupport.getMap(context, "requirement");

            String rawRequirement = String.valueOf(requirementMap.getOrDefault("rawRequirement", ""));
            String mergedRequirement = (rawRequirement + " " + normalizedFeedback).trim();//合并反馈需求

            requirementMap.put("rawRequirement", mergedRequirement);
            requirementMap.put("topic", extractTopic(mergedRequirement));//重新提取主题
            requirementMap.put("keywords", extractSimpleKeywords(mergedRequirement));//重新提取关键字

            context.put("clarification", Map.of("required", false));

            //需求澄清后也要读取 Memory，重新检索RAG
            String memoryContext = workflowStepRunner.run(run.getId(),
                    AiWorkflowStep.MEMORY_RETRIEVE,
                    "正在读取写作偏好...",
                    () -> retrieveMemoryContext(run.getUserId(), mergedRequirement),
                    emitter
            );
            context.put("memoryContext", memoryContext);

            List<Map<String, Object>> ragReferences = workflowStepRunner.run(run.getId(),
                    AiWorkflowStep.RAG_SEARCH,
                    "正在检索站内相关文章...",
                    () -> retrieveRagReferences(mergedRequirement),
                    emitter
            );
            context.put("ragReferences", ragReferences);
            //(同 create 路径的 LLM 大纲生成)
            context.put("outline", workflowStepRunner.run(run.getId(),
                    AiWorkflowStep.GENERATE_OUTLINE,
                    "正在生成文章大纲...",
                    () -> buildOutlineByLLM(run, mergedRequirement, memoryContext, ragReferences, emitter),
                    emitter
            ));

            run.setStatus(AiWorkflowStatus.WAITING_OUTLINE_CONFIRM.name());
            run.setCurrentStep(AiWorkflowStep.GENERATE_OUTLINE.name());
            run.setContextJson(workflowContextSupport.toJson(context));
            workflowContextSupport.touch(run);

            return AiWorkflowAdvanceResult.of(run);
        }

         if (status == AiWorkflowStatus.WAITING_OUTLINE_CONFIRM) {
             context.put("outline", workflowStepRunner.run(run.getId(),
                     AiWorkflowStep.GENERATE_OUTLINE,
                     "正在根据修改意见重写大纲...",
                     () -> rebuildOutline(run, context, normalizedFeedback, emitter),
                     emitter
             ));

            run.setStatus(AiWorkflowStatus.WAITING_OUTLINE_CONFIRM.name());
            run.setCurrentStep(AiWorkflowStep.GENERATE_OUTLINE.name());
        } else if (status == AiWorkflowStatus.WAITING_DRAFT_CONFIRM) {
             context.put("draft", workflowStepRunner.run(run.getId(),
                     AiWorkflowStep.GENERATE_DRAFT,
                     "正在根据修改意见重写草稿...",
                     () -> rebuildDraft(run, context, normalizedFeedback, emitter),
                     emitter
             ));

             context.put("qualityCheck", workflowStepRunner.run(run.getId(),
                     AiWorkflowStep.QUALITY_CHECK,
                     "正在执行质量检查...",
                     () -> buildQualityCheck(context),
                     emitter
             ));

            // 用户打回草稿后，必须重新停在草稿确认点，不能因为质量通过就跳过用户确认。
            run.setStatus(AiWorkflowStatus.WAITING_DRAFT_CONFIRM.name());
            run.setCurrentStep(AiWorkflowStep.QUALITY_CHECK.name());
        } else if (status == AiWorkflowStatus.WAITING_FILL_CONFIRM) {
             context.put("draft", workflowStepRunner.run(run.getId(),
                     AiWorkflowStep.GENERATE_DRAFT,
                     "正在根据修改意见重写草稿...",
                     () -> rebuildDraft(run, context, normalizedFeedback, emitter),
                     emitter
             ));

             context.put("qualityCheck", workflowStepRunner.run(run.getId(),
                     AiWorkflowStep.QUALITY_CHECK,
                     "正在执行质量检查...",
                     () -> buildQualityCheck(context),
                     emitter
             ));
            // 用户在填充前提出修改，说明需要重新看一遍新草稿。
            run.setStatus(AiWorkflowStatus.WAITING_DRAFT_CONFIRM.name());
            run.setCurrentStep(AiWorkflowStep.QUALITY_CHECK.name());
        } else {
            throw new IllegalArgumentException("当前状态不允许提交修改意见");
        }

        run.setContextJson(workflowContextSupport.toJson(context));
        workflowContextSupport.touch(run);

        return AiWorkflowAdvanceResult.of(run);
    }

    /*retry 不是"从头再来"，而是从失败的步骤原地恢复。它和 reject 的区别：


    reject = 用户不满意 → 带着反馈重新生成（回退到确认点）
    retry  = 系统失败了 → 不带反馈重做当前步骤（回到确认点）*/
    @Override
    public AiWorkflowAdvanceResult retry(AiWorkflowRun run,AiWorkflowStepEmitter emitter){
        // 看死在哪
        AiWorkflowStep step = workflowStatusSupport.parseStep(run.getCurrentStep());
        Map<String,Object> context = workflowContextSupport.parseContext(run.getContextJson());

        //需求分析失败（理论上不会发生，防御性兜底）：直接从初始链路重跑
        if (step == AiWorkflowStep.REQUIREMENT_ANALYZE) {
            run.setErrorMessage(null);
            return runInitialSteps(run, emitter);
        }
        if(step == AiWorkflowStep.MEMORY_RETRIEVE
        || step == AiWorkflowStep.RAG_SEARCH
        || step == AiWorkflowStep.GENERATE_OUTLINE){// 死在大纲相关
            Map<String, Object> requirement = workflowContextSupport.getMap(context, "requirement");
            String rawRequirement = String.valueOf(requirement.getOrDefault("rawRequirement", ""));

            // 重读记忆
            String memoryContext = workflowStepRunner.run(run.getId(),
                    AiWorkflowStep.MEMORY_RETRIEVE,
                    "正在读取写作偏好...",
                    () -> retrieveMemoryContext(run.getUserId(), rawRequirement),
                    emitter
            );

            context.put("memoryContext", memoryContext);

            // 重查 RAG
            List<Map<String, Object>> ragReferences = workflowStepRunner.run(run.getId(),
                    AiWorkflowStep.RAG_SEARCH,
                    "正在检索站内相关文章...",
                    () -> retrieveRagReferences(rawRequirement),
                    emitter
            );

            context.put("ragReferences", ragReferences);

            // 重新生成大纲
            context.put("outline", workflowStepRunner.run(run.getId(),
                    AiWorkflowStep.GENERATE_OUTLINE,
                    "正在重新生成文章大纲...",
                    () -> buildOutlineByLLM(run, rawRequirement, memoryContext, ragReferences, emitter),
                    emitter
            ));

            //回到大纲确认
            run.setStatus(AiWorkflowStatus.WAITING_OUTLINE_CONFIRM.name());
            run.setCurrentStep(AiWorkflowStep.GENERATE_OUTLINE.name());
            run.setContextJson(workflowContextSupport.toJson(context));
            run.setErrorMessage(null);
            workflowContextSupport.touch(run);

            return AiWorkflowAdvanceResult.of(run);
        }
        if (step == AiWorkflowStep.GENERATE_DRAFT
                || step == AiWorkflowStep.QUALITY_CHECK) {
            context.put("draft", workflowStepRunner.run(run.getId(),
                    AiWorkflowStep.GENERATE_DRAFT,
                    "正在根据修改意见重写草稿...",
                    () -> buildDraft(run, context, emitter),
                    emitter
            ));

            context.put("qualityCheck", workflowStepRunner.run(run.getId(),
                    AiWorkflowStep.QUALITY_CHECK,
                    "正在执行质量检查...",
                    () -> buildQualityCheck(context),
                    emitter
            ));

            run.setStatus(AiWorkflowStatus.WAITING_DRAFT_CONFIRM.name());
            run.setCurrentStep(AiWorkflowStep.QUALITY_CHECK.name());
            run.setContextJson(workflowContextSupport.toJson(context));
            run.setErrorMessage(null);
            workflowContextSupport.touch(run);

            return AiWorkflowAdvanceResult.of(run);
        }

        if (step == AiWorkflowStep.FILL_ARTICLE) {//失败在填充编辑器
            // 重新生成 fillArticle 指令
            AiEditorCommand editorAction = buildEditorAction(context);

            run.setStatus(AiWorkflowStatus.WAITING_USER_SAVE.name());
            run.setCurrentStep(AiWorkflowStep.FILL_ARTICLE.name());
            run.setErrorMessage(null);
            workflowContextSupport.touch(run);

            return AiWorkflowAdvanceResult.withEditorAction(run, editorAction);
        }

        throw new IllegalArgumentException("当前步骤不支持重试: " + step);
    }
    //加记忆读取方法
    private String retrieveMemoryContext(Long userId, String requirement) {
        if (userId == null) {
            return "";
        }

        try {
            String memoryPrompt = aiUserMemoryService.buildMemoryPrompt(userId, requirement);
            return memoryPrompt == null ? "" : memoryPrompt;
        } catch (Exception e) {
            return "";
        }
    }
    //此方法创建一个workflow的初始状态包，后面整个文章工作流所有中间结果都往这个context塞，最后序列化为 context_json 存到ai_workflow_runs 表里
    private Map<String, Object> buildInitialContext(String requirement) {
        Map<String, Object> context = new HashMap<>();
        context.put("workflowVersion", WORKFLOW_VERSION);

        Map<String, Object> variables = new HashMap<>();
        variables.put("articleType", "TECH");
        variables.put("language", "zh-CN");
        context.put("variables", variables);

        Map<String, Object> requirementMap = new HashMap<>();
        requirementMap.put("rawRequirement", requirement);
        requirementMap.put("topic", extractTopic(requirement));  //提取主题
        requirementMap.put("type", "技术博客");
        requirementMap.put("keywords", extractSimpleKeywords(requirement));//从文本提取关键字
        context.put("requirement", requirementMap);

        context.put("clarification", new HashMap<>());

        context.put("memoryContext", "");
        context.put("ragReferences", new ArrayList<>());
        context.put("outline", "");
        context.put("draft", new HashMap<>());
        context.put("qualityCheck", new HashMap<>());
        context.put("feedbackHistory", new ArrayList<>());

        return context;
    }
    /**
     * 基于写作需求检索站内相关内容，结果写入 context.ragReferences。
     *
     * RAG 检索失败时兜底返回空列表，不阻断 workflow 创建。
     */
    private List<Map<String, Object>> retrieveRagReferences(String requirement) {
        try {
            AiIntent intent = new AiIntent();
            intent.setIntent("ARTICLE_SEARCH");
            intent.setKeyWord(extractTopic(requirement));

            ArticleRagSearchResult result = articleRagSearchService.search(requirement, intent);

            if (result == null || result.contexts() == null || result.contexts().isEmpty()) {
                return new ArrayList<>();
            }

            return result.contexts().stream()
                    .map(this::toRagReference)
                    .toList();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private Map<String, Object> toRagReference(ArticleRagContext context) {
        Map<String, Object> reference = new HashMap<>();
        reference.put("articleId", context.articleId());
        reference.put("title", context.title());
        reference.put("chunkIndex", context.chunkIndex());
        reference.put("snippet", context.content());
        return reference;
    }
    private String rebuildOutline(AiWorkflowRun run, Map<String, Object> context, String feedback, AiWorkflowStepEmitter emitter) {
        Map<String, Object> requirement = workflowContextSupport.getMap(context, "requirement");
        String rawRequirement = String.valueOf(requirement.getOrDefault("rawRequirement", ""));
        String memoryContext = String.valueOf(context.getOrDefault("memoryContext", ""));
        List<Map<String, Object>> ragReferences = getList(context, "ragReferences");

        return buildOutlineByLLM(run, rawRequirement, feedback, memoryContext, ragReferences, emitter);
    }
    private Map<String, Object> rebuildDraft(AiWorkflowRun run, Map<String, Object> context, String feedback, AiWorkflowStepEmitter emitter) {
        // appendFeedback 已经把本次修改意见写入 feedbackHistory。
        // buildDraft 会读取 feedbackHistory，让 LLM 基于完整上下文重写，而不是把意见拼到旧正文尾部。
        return buildDraft(run, context, emitter);
    }
    /**
     * LLM 生成完整文章草稿
     *
     * 输入：requirement + outline + memoryContext + ragReferences + feedbackHistory
     * 输出：title / summary / content / tags / categoryName
     * 失败时回退到模板草稿，不阻断 workflow。
     */
    private Map<String, Object> buildDraft(AiWorkflowRun run, Map<String, Object> context, AiWorkflowStepEmitter emitter) {
        return buildDraftByLLM(run, context, emitter);
    }

    private Map<String, Object> buildDraftByLLM(Map<String, Object> context) {
        try {
            String content = chatClientBuilder.build()
                    .prompt()
                    .options(OpenAiChatOptions.builder()
                            .maxTokens(8500)
                            .build())
                    .system("""
                        你是博客文章创作助手，只输出 JSON，不要输出任何解释、代码块或前后缀。
                        JSON 字段：
                        title：文章标题
                        summary：文章摘要
                        content：完整 Markdown 正文
                        tags：标签字符串数组
                        categoryName：分类名称
                        """)
                    .user(buildDraftUserPrompt(context))
                    .call()
                    .content();

            if (content == null || content.isBlank()) {
                return buildFallbackDraft(context);
            }

            return parseDraftJson(content, context);
        } catch (Exception e) {
            return buildFallbackDraft(context);
        }
    }

    private Map<String, Object> buildDraftByLLM(AiWorkflowRun run, Map<String, Object> context, AiWorkflowStepEmitter emitter) {
        try {
            StringBuilder fullContent = new StringBuilder();
            TokenUsageAccumulator usage = new TokenUsageAccumulator();

            chatClientBuilder.build()
                    .prompt()
                    .options(OpenAiChatOptions.builder()
                            .maxTokens(8500)
                            .streamUsage(true)
                            .build())
                    .system("""
                        你是博客正文生成器。
                        只输出 Markdown 正文。
                        不要输出 JSON。
                        不要输出代码块包裹全文。
                        正文需要结构清晰，有标题、小节、解释和项目实践细节。
                        """)
                    .user(buildDraftMarkdownPrompt(context))
                    .stream()
                    .chatResponse()
                    .timeout(Duration.ofSeconds(60))
                    .doOnNext(response -> {
                        String chunk = response.getResult() == null ? "" : response.getResult().getOutput().getText();
                        if (chunk != null && !chunk.isEmpty()) {
                            fullContent.append(chunk);
                            emitter.emitContent(
                                    AiWorkflowStep.GENERATE_DRAFT.name(),
                                    "draft.content",
                                    chunk
                            );
                        }
                        usage.add(response.getMetadata().getUsage());
                    })
                    .blockLast();

            workflowTokenRecorder.accumulate(run, usage);

            String markdown = fullContent.toString();
            if (markdown.isBlank()) {
                throw new RuntimeException("草稿生成失败：模型返回空内容");
            }

            return buildDraftFromMarkdown(markdown, context);
        } catch (RuntimeException e) {
            //LLM 失败不静默兜底：抛给 runStep 记 FAILED，Service 层 markFailed → 前端可重试；
            //消息分类成友好文案，原始堆栈由 Service 层日志保留
            throw LlmErrorClassifier.wrap("草稿生成失败：", e);
        } catch (Exception e) {
            throw new RuntimeException("草稿生成失败：" + LlmErrorClassifier.friendlyMessage(e), e);
        }
    }

    @SuppressWarnings("unchecked")
    private String buildDraftMarkdownPrompt(Map<String, Object> context) {
        Map<String, Object> requirement = workflowContextSupport.getMap(context, "requirement");

        String rawRequirement = String.valueOf(requirement.getOrDefault("rawRequirement", ""));
        String outline = String.valueOf(context.getOrDefault("outline", ""));
        String memoryContext = String.valueOf(context.getOrDefault("memoryContext", ""));

        Object ragValue = context.get("ragReferences");
        List<Map<String, Object>> ragReferences = ragValue instanceof List<?> list
                ? (List<Map<String, Object>>) list
                : List.of();

        return """
            用户需求：
            %s

            文章大纲：
            %s

            用户长期写作偏好：
            %s

            站内相关文章：
            %s

            历史修改意见：
            %s

            请根据以上信息生成一篇完整博客正文。
            要求：
            1. 使用 Markdown。
            2. 第一行用 # 输出文章标题。
            3. 内容要适合技术博客。
            4. 如果站内已有相关内容，避免重复表述，突出新角度。
            5. 参考历史修改意见调整内容。
            6. 不要输出 JSON。
            7. 不要解释你的生成过程。

            注意：你现在要写的是完整正文，不是大纲。
            禁止只输出标题列表、目录、提纲、要点列表。
            每个二级标题下面必须有完整段落解释。
            每个小节至少包含 2 段正文说明。
            如果使用列表，列表前后必须有解释性段落。
            最终内容应该像一篇可以直接发布的技术博客。
            """.formatted(
                rawRequirement,
                outline,
                workflowContextSupport.isBlank(memoryContext) ? "无" : memoryContext,
                summarizeRagReferences(ragReferences),
                summarizeFeedbackHistory(context)
        );
    }

    private Map<String, Object> buildDraftFromMarkdown(String markdown, Map<String, Object> context) {
        Map<String, Object> requirement = workflowContextSupport.getMap(context, "requirement");

        String title = extractMarkdownTitle(markdown);
        if (workflowContextSupport.isBlank(title)) {
            title = String.valueOf(requirement.getOrDefault("topic", "未命名文章"));
        }

        Map<String, Object> draft = new HashMap<>();
        draft.put("title", title);
        draft.put("summary", buildSummaryFromMarkdown(markdown));
        draft.put("content", markdown);
        draft.put("tags", requirement.getOrDefault("keywords", List.of()));
        draft.put("categoryName", "随笔");

        return draft;
    }

    private String extractMarkdownTitle(String markdown) {
        if (markdown == null) {
            return "";
        }

        for (String line : markdown.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                return trimmed.substring(2).trim();
            }
        }

        return "";
    }

    private String buildSummaryFromMarkdown(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }

        String plain = markdown
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("[`*_>#\\-]", "")
                .replaceAll("\\s+", " ")
                .trim();

        if (plain.length() <= 120) {
            return plain;
        }

        return plain.substring(0, 120) + "...";
    }

    @SuppressWarnings("unchecked")
    private String buildDraftUserPrompt(Map<String, Object> context) {
        Map<String, Object> requirement = workflowContextSupport.getMap(context, "requirement");
        String rawRequirement = String.valueOf(requirement.getOrDefault("rawRequirement", ""));
        String outline = String.valueOf(context.getOrDefault("outline", ""));
        String memoryContext = String.valueOf(context.getOrDefault("memoryContext", ""));

        Object ragValue = context.get("ragReferences");
        List<Map<String, Object>> ragReferences = ragValue instanceof List<?> list
                ? (List<Map<String, Object>>) list
                : List.of();

        return """
            写作需求：
            %s

            已确认大纲：
            %s

            用户长期记忆：
            %s

            站内相关文章：
            %s

            历史修改意见：
            %s

            请基于以上内容生成一篇完整的技术博客文章草稿，要求：
            1. 严格按照大纲展开，覆盖每个小节
            2. 内容真实、具体，体现技术深度和项目实践
            3. 如果站内已有相关内容，避免重复表述，突出新角度
            4. 只输出 JSON，不要输出任何其他内容
            """.formatted(
                rawRequirement,
                outline,
                workflowContextSupport.isBlank(memoryContext) ? "无" : memoryContext,
                summarizeRagReferences(ragReferences),
                summarizeFeedbackHistory(context)
        );
    }

    /**
     * 历史修改意见只取最近 3 条，防止 prompt 无限变长。
     */
    @SuppressWarnings("unchecked")
    private String summarizeFeedbackHistory(Map<String, Object> context) {
        Object value = context.get("feedbackHistory");
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return "无";
        }

        int limit = Math.min(list.size(), 3);
        int from = list.size() - limit;

        StringBuilder sb = new StringBuilder();
        for (int i = from; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof Map<?, ?> feedbackMap) {
                Object userFeedback = feedbackMap.get("userFeedback");
                sb.append("- ").append(userFeedback == null ? "" : String.valueOf(userFeedback)).append("\n");
            }
        }

        return sb.toString().trim();
    }

    private Map<String, Object> parseDraftJson(String raw, Map<String, Object> context) {
        Map<String, Object> parsed;
        try {
            parsed = objectMapper.readValue(cleanMarkdown(raw), new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            return buildFallbackDraft(context);
        }

        Object titleValue = parsed.get("title");
        Object contentValue = parsed.get("content");

        Map<String, Object> draft = new HashMap<>();
        draft.put("title", titleValue == null ? "" : String.valueOf(titleValue));
        draft.put("summary", parsed.get("summary") == null ? "" : String.valueOf(parsed.get("summary")));
        draft.put("content", contentValue == null ? "" : String.valueOf(contentValue));
        draft.put("tags", normalizeTags(parsed.get("tags")));
        draft.put("categoryName", parsed.get("categoryName") == null ? "" : String.valueOf(parsed.get("categoryName")));

        // 关键字段缺失视为生成失败，回退模板草稿
        if (workflowContextSupport.isBlank(draft.get("title")) || workflowContextSupport.isBlank(draft.get("content"))) {
            return buildFallbackDraft(context);
        }

        return draft;
    }

    private List<String> normalizeTags(Object rawTags) {
        if (rawTags instanceof List<?> list) {
            List<String> tags = new ArrayList<>();
            for (Object tag : list) {
                String text = String.valueOf(tag).trim();
                if (!text.isBlank()) {
                    tags.add(text);
                }
            }
            return tags;
        }
        if (rawTags instanceof String text && !text.isBlank()) {
            return List.of(text.trim());
        }
        return new ArrayList<>();
    }

    /**
     * 模板草稿兜底，LLM 不可用时使用。
     */
    private Map<String, Object> buildFallbackDraft(Map<String, Object> context) {
        Map<String, Object> requirement = workflowContextSupport.getMap(context, "requirement");
        String topic = String.valueOf(requirement.getOrDefault("topic", "技术博客"));
        String outline = String.valueOf(context.getOrDefault("outline", ""));
        String feedbackSummary = summarizeFeedbackHistory(context);

        Map<String, Object> draft = new HashMap<>();
        draft.put("title", topic);
        draft.put("summary", "本文围绕 " + topic + " 展开，结合原理、场景和项目实践进行说明。");
        draft.put("content", """
                # %s

                以下内容基于已确认大纲生成。

                %s

                ## 正文草稿

                这里是文章创作 Workflow 模板兜底生成的占位正文。
                当 LLM 不可用或生成失败时使用。

                ## 最近修改意见

                %s
                """.formatted(topic, outline, "无".equals(feedbackSummary) ? "暂无" : feedbackSummary));
        draft.put("tags", extractSimpleKeywords(topic));
        draft.put("categoryName", "随笔");

        return draft;
    }
    private List<String> extractSimpleKeywords(String text) {
        List<String> keywords = new ArrayList<>();
        String normalized = text == null ? "" : text.trim();

        if (normalized.contains("Redis")) {
            keywords.add("Redis");
        }
        if (normalized.contains("缓存")) {
            keywords.add("缓存");
        }
        if (normalized.contains("高并发")) {
            keywords.add("高并发");
        }

        if (keywords.isEmpty() && !normalized.isBlank()) {
            keywords.add(normalized.length() > 20 ? normalized.substring(0, 20) : normalized);
        }

        return keywords;
    }
    /**
     * 规则型质量检查，不调用 LLM。
     *
     * issues：硬伤，不修复则 passed=false，工作流停在 WAITING_DRAFT_CONFIRM
     * suggestions：软提示，仅展示给用户参考，不阻塞流程
     */
    private Map<String, Object> buildQualityCheck(Map<String, Object> context) {
        Map<String, Object> draft = workflowContextSupport.getMap(context, "draft");
        Map<String, Object> requirement = workflowContextSupport.getMap(context, "requirement");
        String outline = String.valueOf(context.getOrDefault("outline", ""));
        String memoryContext = String.valueOf(context.getOrDefault("memoryContext", ""));
        List<Map<String, Object>> ragReferences = getList(context, "ragReferences");

        String title = String.valueOf(draft.getOrDefault("title", ""));
        String summary = String.valueOf(draft.getOrDefault("summary", ""));
        String content = String.valueOf(draft.getOrDefault("content", ""));

        List<String> issues = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();

        // 1. 标题、摘要、正文不能为空
        if (workflowContextSupport.isBlank(title)) {
            issues.add("标题不能为空");
        }
        if (workflowContextSupport.isBlank(summary)) {
            issues.add("摘要不能为空");
        }
        if (workflowContextSupport.isBlank(content)) {
            issues.add("正文不能为空");
        }

        // 2. 正文覆盖大纲小节
        List<String> outlineSections = extractOutlineSections(outline);
        if (!outlineSections.isEmpty() && !workflowContextSupport.isBlank(content)) {
            long matched = outlineSections.stream().filter(content::contains).count();

            if (matched == 0) {
                issues.add("正文未体现已确认大纲中的任何小节，与大纲脱节");
            } else if (matched < outlineSections.size() / 2.0) {
                suggestions.add("正文仅覆盖大纲 " + matched + "/" + outlineSections.size()
                        + " 个小节，建议补充遗漏内容");
            }
        }

        // 3. 正文提到主题关键词
        String rawRequirement = String.valueOf(requirement.getOrDefault("rawRequirement", ""));
        List<String> keywords = extractSimpleKeywords(rawRequirement);
        if (!keywords.isEmpty() && !workflowContextSupport.isBlank(content)) {
            boolean hitAny = keywords.stream().anyMatch(content::contains);
            if (!hitAny) {
                suggestions.add("正文未明显提到主题关键词（" + String.join("、", keywords) + "），请确认是否偏离主题");
            }
        }

        // 4. 记忆偏好：正文是否有技术深度倾向
        if (!workflowContextSupport.isBlank(memoryContext) && !workflowContextSupport.isBlank(content)) {
            boolean hasDepth = containsAny(content,
                    "原理", "机制", "源码", "底层", "架构", "设计", "性能",
                    "实践", "实战", "深入", "实现");
            if (!hasDepth) {
                suggestions.add("用户记忆偏好偏技术深度，建议补充原理 / 实现 / 实践相关分析");
            }
        }

        // 5. 站内文章重复风险
        if (!ragReferences.isEmpty() && !workflowContextSupport.isBlank(content)) {
            for (Map<String, Object> reference : ragReferences) {
                String ragTitle = String.valueOf(reference.getOrDefault("title", ""));
                if (ragTitle.isBlank()) {
                    continue;
                }
                if (title.contains(ragTitle) || content.contains(ragTitle)) {
                    suggestions.add("站内已有文章《" + ragTitle + "》，标题或内容与之重叠，建议避免重复表述、突出新角度");
                }
            }
        }

        // 6. 大纲检测：正文像大纲或要点列表
        if (!workflowContextSupport.isBlank(content) && looksLikeOutline(content)) {
            issues.add("正文像大纲或要点列表，缺少完整段落展开");
        }

        Map<String, Object> qualityCheck = new HashMap<>();
        qualityCheck.put("passed", issues.isEmpty());
        qualityCheck.put("issues", issues);
        qualityCheck.put("suggestions", suggestions);

        return qualityCheck;
    }

    /** 检测 markdown 是否像大纲/目录而非完整正文：标题多 + 列表多 + 长段落少 */
    private boolean looksLikeOutline(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return false;
        }

        String[] lines = markdown.split("\\R");

        int headingCount = 0;
        int listCount = 0;
        int longParagraphCount = 0;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("#")) {
                headingCount++;
            } else if (trimmed.startsWith("- ") || trimmed.matches("^\\d+\\.\\s+.*")) {
                listCount++;
            } else if (trimmed.length() >= 80) {
                longParagraphCount++;
            }
        }

        return headingCount >= 3 && listCount >= 5 && longParagraphCount < 3;
    }

    /**
     * 从大纲 Markdown 中提取小节标题，排除首个 # 文章标题。
     * 支持 "1. 小节" 和 "## 小节" 两种写法。
     */
    private List<String> extractOutlineSections(String outline) {
        List<String> sections = new ArrayList<>();
        if (outline == null || outline.isBlank()) {
            return sections;
        }

        for (String line : outline.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.matches("^\\d+\\.\\s+.*") || trimmed.matches("^#{2,}\\s+.*")) {
                sections.add(trimmed
                        .replaceFirst("^\\d+\\.\\s+", "")
                        .replaceFirst("^#{2,}\\s+", "")
                        .trim());
            }
        }

        return sections;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getList(Map<String, Object> context, String key) {
        Object value = context.get(key);
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        List<Map<String, Object>> empty = new ArrayList<>();
        context.put(key, empty);
        return empty;
    }

    //从 draft 构建 fillArticle 指令
    private AiEditorCommand buildEditorAction(Map<String, Object> context) {
        Map<String, Object> draft = workflowContextSupport.getMap(context, "draft");

        AiEditorCommand command = new AiEditorCommand();
        command.setType("fillArticle");
        command.setTitle(String.valueOf(draft.getOrDefault("title", "")));
        command.setSummary(String.valueOf(draft.getOrDefault("summary", "")));
        command.setContent(String.valueOf(draft.getOrDefault("content", "")));
        command.setCategoryName(String.valueOf(draft.getOrDefault("categoryName", "随笔")));

        return command;
    }
    //从原文提取主题：循环剥掉请求框架词，剥空返回空。
    //不回退原文——回退会把"我想让你帮我写一篇文章"这种无主题输入误判成有主题。
    private String extractTopic(String requirement) {
        String topic = requirement == null ? "" : requirement.trim();
        if (topic.isBlank()) {
            return "";
        }

        String before;
        do {
            before = topic;
            topic = topic
                    .replaceFirst("^(我想让你|我想请你|请你|我想|你帮我|帮我|请帮我|麻烦帮我)", "")
                    .replaceFirst("^(写|生成|创作|写点|写个|写篇|来一篇|整一篇)", "")
                    .replaceFirst("^(一篇文章|篇文章|一篇|一份|一个)", "")
                    .trim();
        } while (!topic.equals(before) && !topic.isBlank());

        //剥掉结尾的文体词
        topic = topic.replaceFirst("(技术博客|博客|文章|帖子)$", "").trim();
        return topic;
    }

    //判断需求是否模糊（公开给 AiMessageServiceImpl 的次入口做确定性裁判）。
    //主题必须能从原文提取出实义内容：剥空 / 太短 / 纯虚词都算模糊。
    //只碰原始 requirement，不依赖 LLM 输出的 topic——LLM 判断"是不是想写文章"，规则判断"写什么"。
    public boolean isRequirementUnclear(String requirement) {
        String topic = extractTopic(requirement);

        if (topic == null || topic.isBlank()) {
            return true;
        }

        String normalized = topic.replaceAll("\\s+", "");
        if (normalized.length() < 4) {
            return true;
        }

        return containsAny(normalized,
                "随便",
                "都行",
                "不知道",
                "没想好",
                "东西",
                "啥",
                "什么");
    }
    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
    private Map<String, Object> buildRequirementClarification() {
        Map<String, Object> clarification = new HashMap<>();
        clarification.put("required", true);
        clarification.put("question", "你想写哪个主题？可以直接告诉我主题，比如 Redis 缓存、Kafka 消息队列、RAG 检索增强、项目复盘等。");
        return clarification;
    }

    //接入LLM
    private String buildOutlineByLLM(AiWorkflowRun run,
                                     String requirement,
                                     String memoryContext,
                                     List<Map<String, Object>> ragReferences,
                                     AiWorkflowStepEmitter emitter) {
        return buildOutlineByLLM(run, requirement, null, memoryContext, ragReferences, emitter);
    }

    private String buildOutlineByLLM(AiWorkflowRun run,
                                     String requirement,
                                     String feedback,
                                     String memoryContext,
                                     List<Map<String, Object>> ragReferences,
                                     AiWorkflowStepEmitter emitter) {
        try {
            StringBuilder fullContent = new StringBuilder();
            TokenUsageAccumulator usage = new TokenUsageAccumulator();

            chatClientBuilder.build()
                    .prompt()
                    .options(OpenAiChatOptions.builder()
                            .streamUsage(true)
                            .build())
                    .system("""
                        你是博客文章大纲生成器，只输出 Markdown。
                        不要输出解释，不要输出代码块，不要输出多余前后缀。
                        必须结合用户需求、长期记忆和站内相关文章参考。
                        大纲要体现真实技术点、项目实践和知识体系一致性。
                        """)
                    .user(buildOutlineUserPrompt(requirement, feedback, memoryContext, ragReferences))
                    .stream()
                    .chatResponse()
                    .timeout(Duration.ofSeconds(60))
                    .doOnNext(response -> {
                        String chunk = response.getResult() == null ? "" : response.getResult().getOutput().getText();
                        if (chunk != null && !chunk.isEmpty()) {
                            fullContent.append(chunk);
                            emitter.emitContent(
                                    AiWorkflowStep.GENERATE_OUTLINE.name(),
                                    "outline",
                                    chunk
                            );
                        }
                        usage.add(response.getMetadata().getUsage());
                    })
                    .blockLast();

            workflowTokenRecorder.accumulate(run, usage);

            String content = fullContent.toString();
            if (content.isBlank()) {
                throw new RuntimeException("大纲生成失败：模型返回空内容");
            }

            return cleanMarkdown(content);
        } catch (RuntimeException e) {
            //LLM 失败不静默兜底：抛给 runStep 记 FAILED，Service 层 markFailed → 前端可重试；
            //消息分类成友好文案，原始堆栈由 Service 层日志保留
            throw LlmErrorClassifier.wrap("大纲生成失败：", e);
        } catch (Exception e) {
            throw new RuntimeException("大纲生成失败：" + LlmErrorClassifier.friendlyMessage(e), e);
        }
    }
    //拼写prompt：需求 + 记忆 + RAG站内文章参考
    private String buildOutlineUserPrompt(String requirement,
                                          String feedback,
                                          String memoryContext,
                                          List<Map<String, Object>> ragReferences) {
        return """
            用户需求：
            %s

            用户修改意见：
            %s

            用户长期记忆：
            %s

            站内相关内容：
            %s

            请生成一个技术博客大纲，要求：
            1. 先给出标题
            2. 5 到 6 个结构清晰的小节(或者看具体情况给出小节)
            3. 必须包含原理、场景、问题、实践
            4. 如果站内已有相关内容，避免重复旧文章表述，突出新的角度
            5. 只输出 Markdown 大纲
            """.formatted(
                requirement,
                workflowContextSupport.isBlank(feedback) ? "无" : feedback,
                workflowContextSupport.isBlank(memoryContext) ? "无" : memoryContext,
                summarizeRagReferences(ragReferences)
        );
    }

    private String summarizeRagReferences(List<Map<String, Object>> ragReferences) {
        if (ragReferences == null || ragReferences.isEmpty()) {
            return "无";
        }

        StringBuilder sb = new StringBuilder();
        int limit = Math.min(ragReferences.size(), 5);

        for (int i = 0; i < limit; i++) {
            Map<String, Object> reference = ragReferences.get(i);
            sb.append(i + 1).append(". ");
            sb.append("标题：").append(String.valueOf(reference.getOrDefault("title", "")));
            sb.append("；摘要：").append(String.valueOf(reference.getOrDefault("snippet", "")));
            sb.append("\n");
        }

        return sb.toString().trim();
    }
    //去掉 ```markdown 包裹
    private String cleanMarkdown(String raw) {
        if (raw == null) {
            return "";
        }

        String text = raw.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*\\s*", "");
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3).trim();
            }
        }

        return text;
    }

    /** 把质量检查结果转成模型能理解的错误反馈 */
    private String buildQualityFeedbackForModel(Map<String, Object> qualityCheck) {
        List<String> issues = getStringList(qualityCheck.get("issues"));
        List<String> suggestions = getStringList(qualityCheck.get("suggestions"));

        StringBuilder feedback = new StringBuilder();
        feedback.append("系统质量检查未通过，请重新生成完整正文，不要只输出大纲或要点列表。\n");

        if (!issues.isEmpty()) {
            feedback.append("必须修复的问题：\n");
            for (String issue : issues) {
                feedback.append("- ").append(issue).append("\n");
            }
        }

        if (!suggestions.isEmpty()) {
            feedback.append("建议改进：\n");
            for (String suggestion : suggestions) {
                feedback.append("- ").append(suggestion).append("\n");
            }
        }

        feedback.append("要求：每个小节必须有完整段落解释，最终内容要像一篇可直接发布的技术博客。");
        return feedback.toString();
    }

    private List<String> getStringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        return List.of();
    }
}
