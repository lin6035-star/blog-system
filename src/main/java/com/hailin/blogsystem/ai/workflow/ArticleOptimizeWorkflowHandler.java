package com.hailin.blogsystem.ai.workflow;

import com.hailin.blogsystem.ai.LlmErrorClassifier;
import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.ai.rag.ArticleRagSearchService;
import com.hailin.blogsystem.entity.AiEditorCommand;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import com.hailin.blogsystem.entity.dto.ArticleRagSearchResult;
import com.hailin.blogsystem.entity.dto.AiWorkflowOptimizeArticleDTO;
import com.hailin.blogsystem.entity.dto.AiWorkflowStatus;
import com.hailin.blogsystem.entity.dto.AiWorkflowStep;
import com.hailin.blogsystem.entity.dto.AiWorkflowType;
import com.hailin.blogsystem.service.ArticlesService;
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

/*文章优化工作流：
    create → LOAD_ARTICLE → ANALYZE_ARTICLE → MEMORY_RETRIEVE → RAG_SEARCH → GENERATE_OPTIMIZATION_PLAN
    → WAITING_PLAN_CONFIRM   （优化方案已生成，等用户确认）
    → REWRITE_ARTICLE → CONTENT_CHECK
    → WAITING_DRAFT_CONFIRM  （优化稿已生成，等用户确认）
    → FILL_ARTICLE
    → WAITING_USER_SAVE      （已填充编辑器，等用户保存）
    → COMPLETED
每个等待态都可以 approve（同意推进）或 reject（带着意见重做当前阶段）
*/

@Component
@RequiredArgsConstructor
public class ArticleOptimizeWorkflowHandler implements WorkflowHandler {

    private static final String WORKFLOW_VERSION = "1.0";

    private final ArticlesService articlesService;
    private final ArticleRagSearchService articleRagSearchService;
    private final AiUserMemoryService aiUserMemoryService;
    private final ChatClient.Builder chatClientBuilder;
    private final WorkflowStepRunner workflowStepRunner;
    private final WorkflowContextSupport workflowContextSupport;
    private final WorkflowStatusSupport workflowStatusSupport;
    private final WorkflowTokenRecorder workflowTokenRecorder;

    @Override
    public String workflowType() {
        return AiWorkflowType.OPTIMIZE_ARTICLE.name();
    }

    // ==================== create ====================

    public AiWorkflowAdvanceResult create(Long userId, AiWorkflowOptimizeArticleDTO dto) {
        return create(userId, dto, AiWorkflowStepEmitter.noop());
    }

    //create 只初始化 run（不执行 LLM），runInitialSteps 在 Service save 后调用
    public AiWorkflowAdvanceResult create(Long userId, AiWorkflowOptimizeArticleDTO dto, AiWorkflowStepEmitter emitter) {
        Long articleId = dto == null ? null : dto.getArticleId();
        if (articleId == null) {
            throw new IllegalArgumentException("待优化文章不能为空");
        }

        // 关键：创建 run 之前先校验权限
        validateArticleOwner(articleId, userId);

        Map<String, Object> context = buildInitialContext(dto);

        AiWorkflowRun run = new AiWorkflowRun();
        run.setUserId(userId);
        run.setConversationId(dto.getConversationId());
        run.setWorkflowType(AiWorkflowType.OPTIMIZE_ARTICLE.name());
        run.setWorkflowVersion(WORKFLOW_VERSION);
        run.setStatus(AiWorkflowStatus.RUNNING.name());
        run.setCurrentStep(AiWorkflowStep.LOAD_ARTICLE.name());
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

    // ==================== runInitialSteps：跑到方案确认 ====================

    //LOAD_ARTICLE → ANALYZE_ARTICLE → MEMORY_RETRIEVE → RAG_SEARCH → GENERATE_OPTIMIZATION_PLAN → WAITING_PLAN_CONFIRM
    public AiWorkflowAdvanceResult runInitialSteps(AiWorkflowRun run, AiWorkflowStepEmitter emitter) {
        AiWorkflowStepEmitter safeEmitter = emitter == null ? AiWorkflowStepEmitter.noop() : emitter;
        Map<String, Object> context = workflowContextSupport.parseContext(run.getContextJson());
        Map<String, Object> stepResults = workflowContextSupport.getStepResults(context);

        //1. LOAD_ARTICLE：加载文章并校验作者（业务 Handler 允许依赖业务服务）
        Map<String, Object> articleInfo = workflowStepRunner.run(
                run.getId(),
                AiWorkflowStep.LOAD_ARTICLE,
                "正在加载文章...",
                () -> loadArticle(getInputArticleId(context), run.getUserId()),
                safeEmitter
        );
        stepResults.put("article", articleInfo);

        //2. ANALYZE_ARTICLE：只分析，不改内容
        Map<String, Object> analysis = workflowStepRunner.run(
                run.getId(),
                AiWorkflowStep.ANALYZE_ARTICLE,
                "正在分析文章现状...",
                () -> analyzeArticle(articleInfo),
                safeEmitter
        );
        stepResults.put("analysis", analysis);

        //3. MEMORY_RETRIEVE：读取用户长期记忆
        String memoryContext = workflowStepRunner.run(
                run.getId(),
                AiWorkflowStep.MEMORY_RETRIEVE,
                "正在读取写作偏好...",
                () -> retrieveMemoryContext(run.getUserId(), String.valueOf(articleInfo.get("title"))),
                safeEmitter
        );
        context.put("memoryContext", memoryContext);

        //4. RAG_SEARCH：以文章主题检索站内相关文章
        List<Map<String, Object>> ragReferences = workflowStepRunner.run(
                run.getId(),
                AiWorkflowStep.RAG_SEARCH,
                "正在检索站内相关文章...",
                () -> retrieveRagReferences(articleInfo),
                safeEmitter
        );
        context.put("ragContext", Map.of("references", ragReferences));

        //5. GENERATE_OPTIMIZATION_PLAN：只生成方案，不生成最终文章
        String plan = workflowStepRunner.run(
                run.getId(),
                AiWorkflowStep.GENERATE_OPTIMIZATION_PLAN,
                "正在生成优化方案...",
                () -> buildOptimizationPlan(run, articleInfo, getInputInstruction(context), memoryContext, ragReferences, safeEmitter),
                safeEmitter
        );
        stepResults.put("optimizationPlan", plan);

        //初始链路结束：停在方案确认
        run.setStatus(AiWorkflowStatus.WAITING_PLAN_CONFIRM.name());
        run.setCurrentStep(AiWorkflowStep.GENERATE_OPTIMIZATION_PLAN.name());
        run.setContextJson(workflowContextSupport.toJson(context));
        workflowContextSupport.touch(run);

        return AiWorkflowAdvanceResult.of(run);
    }

    // ==================== approve ====================

    //WAITING_PLAN_CONFIRM → REWRITE_ARTICLE + CONTENT_CHECK → WAITING_DRAFT_CONFIRM
    //WAITING_DRAFT_CONFIRM → FILL_ARTICLE → WAITING_USER_SAVE
    @Override
    public AiWorkflowAdvanceResult approve(AiWorkflowRun run, AiWorkflowStepEmitter emitter) {
        AiWorkflowStepEmitter safeEmitter = emitter == null ? AiWorkflowStepEmitter.noop() : emitter;
        AiWorkflowStatus status = workflowStatusSupport.parseStatus(run.getStatus());
        Map<String, Object> context = workflowContextSupport.parseContext(run.getContextJson());
        Map<String, Object> stepResults = workflowContextSupport.getStepResults(context);

        switch (status) {
            case WAITING_PLAN_CONFIRM -> {
                String optimizedContent = workflowStepRunner.run(
                        run.getId(),
                        AiWorkflowStep.REWRITE_ARTICLE,
                        "正在按方案重写文章...",
                        () -> rewriteArticleByLLM(run, context, null, safeEmitter),
                        safeEmitter
                );
                stepResults.put("optimizedContent", optimizedContent);

                Map<String, Object> contentCheck = workflowStepRunner.run(
                        run.getId(),
                        AiWorkflowStep.CONTENT_CHECK,
                        "正在检查优化结果...",
                        () -> buildContentCheck(optimizedContent, context),
                        safeEmitter
                );
                stepResults.put("contentCheck", contentCheck);

                // 第一次不合格 → 把检查问题喂回模型，自动重写一次
                if (Boolean.FALSE.equals(contentCheck.get("passed"))) {
                    String systemFeedback = buildQualityFeedbackForModel(contentCheck);

                    workflowContextSupport.appendFeedback(
                            context,
                            AiWorkflowStep.CONTENT_CHECK.name(),
                            AiWorkflowStatus.RUNNING.name(),
                            systemFeedback
                    );

                    String retriedContent = workflowStepRunner.run(
                            run.getId(),
                            AiWorkflowStep.REWRITE_ARTICLE,
                            "优化结果未通过检查，正在重新改写...",
                            () -> rewriteArticleByLLM(run, context, systemFeedback, safeEmitter),
                            safeEmitter
                    );
                    stepResults.put("optimizedContent", retriedContent);

                    contentCheck = workflowStepRunner.run(
                            run.getId(),
                            AiWorkflowStep.CONTENT_CHECK,
                            "正在重新执行质量检查...",
                            () -> buildContentCheck(retriedContent, context),
                            safeEmitter
                    );
                    stepResults.put("contentCheck", contentCheck);
                }

                run.setStatus(AiWorkflowStatus.WAITING_DRAFT_CONFIRM.name());
                run.setCurrentStep(AiWorkflowStep.CONTENT_CHECK.name());
            }
            case WAITING_DRAFT_CONFIRM -> {
                AiEditorCommand editorAction = workflowStepRunner.run(
                        run.getId(),
                        AiWorkflowStep.FILL_ARTICLE,
                        "正在填充编辑器...",
                        () -> buildEditorAction(context),
                        safeEmitter
                );

                run.setStatus(AiWorkflowStatus.WAITING_USER_SAVE.name());
                run.setCurrentStep(AiWorkflowStep.FILL_ARTICLE.name());
                run.setContextJson(workflowContextSupport.toJson(context));
                workflowContextSupport.touch(run);

                return AiWorkflowAdvanceResult.withEditorAction(run, editorAction);
            }
            default -> throw new IllegalArgumentException("当前状态不允许同意操作");
        }

        run.setContextJson(workflowContextSupport.toJson(context));
        workflowContextSupport.touch(run);
        return AiWorkflowAdvanceResult.of(run);
    }

    // ==================== reject ====================

    //WAITING_PLAN_CONFIRM → 带意见重新生成方案 → WAITING_PLAN_CONFIRM
    //WAITING_DRAFT_CONFIRM → 带意见重新重写 + 检查 → WAITING_DRAFT_CONFIRM
    @Override
    public AiWorkflowAdvanceResult reject(AiWorkflowRun run, String feedback, AiWorkflowStepEmitter emitter) {
        AiWorkflowStepEmitter safeEmitter = emitter == null ? AiWorkflowStepEmitter.noop() : emitter;
        String normalizedFeedback = workflowContextSupport.normalizeRequired(feedback, "修改意见不能为空");
        AiWorkflowStatus status = workflowStatusSupport.parseStatus(run.getStatus());
        Map<String, Object> context = workflowContextSupport.parseContext(run.getContextJson());
        Map<String, Object> stepResults = workflowContextSupport.getStepResults(context);
        workflowContextSupport.appendFeedback(context, run.getCurrentStep(), run.getStatus(), normalizedFeedback);

        switch (status) {
            case WAITING_PLAN_CONFIRM -> {
                String plan = workflowStepRunner.run(
                        run.getId(),
                        AiWorkflowStep.GENERATE_OPTIMIZATION_PLAN,
                        "正在按意见重新生成优化方案...",
                        () -> buildOptimizationPlan(
                                run,
                                workflowContextSupport.getMap(stepResults, "article"),
                                getInputInstruction(context),
                                String.valueOf(context.getOrDefault("memoryContext", "")),
                                getRagReferences(context),
                                normalizedFeedback,
                                safeEmitter
                        ),
                        safeEmitter
                );
                stepResults.put("optimizationPlan", plan);
            }
            case WAITING_DRAFT_CONFIRM -> {
                String optimizedContent = workflowStepRunner.run(
                        run.getId(),
                        AiWorkflowStep.REWRITE_ARTICLE,
                        "正在按意见重新改写...",
                        () -> rewriteArticleByLLM(run, context, normalizedFeedback, safeEmitter),
                        safeEmitter
                );
                stepResults.put("optimizedContent", optimizedContent);

                Map<String, Object> contentCheck = workflowStepRunner.run(
                        run.getId(),
                        AiWorkflowStep.CONTENT_CHECK,
                        "正在检查优化结果...",
                        () -> buildContentCheck(optimizedContent, context),
                        safeEmitter
                );
                stepResults.put("contentCheck", contentCheck);
            }
            default -> throw new IllegalArgumentException("当前状态不允许提交修改意见");
        }

        run.setContextJson(workflowContextSupport.toJson(context));
        workflowContextSupport.touch(run);
        return AiWorkflowAdvanceResult.of(run);
    }

    // ==================== retry ====================

    //初始链路步骤失败 → 从初始链路恢复到 WAITING_PLAN_CONFIRM
    //REWRITE/CONTENT_CHECK 失败 → 恢复到 WAITING_DRAFT_CONFIRM
    //FILL_ARTICLE 失败 → 重新返回 editorAction
    @Override
    public AiWorkflowAdvanceResult retry(AiWorkflowRun run, AiWorkflowStepEmitter emitter) {
        AiWorkflowStep step = workflowStatusSupport.parseStep(run.getCurrentStep());

        switch (step) {
            case LOAD_ARTICLE, ANALYZE_ARTICLE, MEMORY_RETRIEVE, RAG_SEARCH, GENERATE_OPTIMIZATION_PLAN -> {
                run.setErrorMessage(null);
                return runInitialSteps(run, emitter);
            }
            case REWRITE_ARTICLE, CONTENT_CHECK -> {
                AiWorkflowStepEmitter safeEmitter = emitter == null ? AiWorkflowStepEmitter.noop() : emitter;
                Map<String, Object> context = workflowContextSupport.parseContext(run.getContextJson());
                Map<String, Object> stepResults = workflowContextSupport.getStepResults(context);

                String optimizedContent = workflowStepRunner.run(
                        run.getId(),
                        AiWorkflowStep.REWRITE_ARTICLE,
                        "正在重新改写...",
                        () -> rewriteArticleByLLM(run, context, null, safeEmitter),
                        safeEmitter
                );
                stepResults.put("optimizedContent", optimizedContent);

                Map<String, Object> contentCheck = workflowStepRunner.run(
                        run.getId(),
                        AiWorkflowStep.CONTENT_CHECK,
                        "正在检查优化结果...",
                        () -> buildContentCheck(optimizedContent, context),
                        safeEmitter
                );
                stepResults.put("contentCheck", contentCheck);

                run.setStatus(AiWorkflowStatus.WAITING_DRAFT_CONFIRM.name());
                run.setCurrentStep(AiWorkflowStep.CONTENT_CHECK.name());
                run.setContextJson(workflowContextSupport.toJson(context));
                run.setErrorMessage(null);
                workflowContextSupport.touch(run);
            }
            case FILL_ARTICLE -> {
                Map<String, Object> context = workflowContextSupport.parseContext(run.getContextJson());
                AiEditorCommand editorAction = buildEditorAction(context);

                run.setStatus(AiWorkflowStatus.WAITING_USER_SAVE.name());
                run.setCurrentStep(AiWorkflowStep.FILL_ARTICLE.name());
                run.setContextJson(workflowContextSupport.toJson(context));
                run.setErrorMessage(null);
                workflowContextSupport.touch(run);

                return AiWorkflowAdvanceResult.withEditorAction(run, editorAction);
            }
            default -> throw new IllegalArgumentException("当前步骤不支持重试: " + step);
        }

        return AiWorkflowAdvanceResult.of(run);
    }

    // ==================== 步骤实现 ====================

    //加载文章并校验作者（只能优化自己的文章），返回精简信息放入 context
    private Map<String, Object> loadArticle(Long articleId, Long userId) {
        Articles article = articlesService.getById(articleId);
        if (article == null) {
            throw new IllegalArgumentException("文章不存在或已删除");
        }
        if (userId == null || !userId.equals(article.getAuthorId())) {
            throw new IllegalArgumentException("只能优化自己的文章");
        }

        Map<String, Object> info = new HashMap<>();
        info.put("id", article.getId());
        info.put("title", article.getTitle());
        info.put("summary", article.getSummary());
        info.put("content", article.getContent());
        info.put("categoryId", article.getCategoryId());
        info.put("status", article.getStatus());
        return info;
    }

    //只分析，不改内容：长度、结构、代码块、图片等规则统计
    private Map<String, Object> analyzeArticle(Map<String, Object> articleInfo) {
        String content = String.valueOf(articleInfo.getOrDefault("content", ""));
        String title = String.valueOf(articleInfo.getOrDefault("title", ""));

        Map<String, Object> analysis = new HashMap<>();
        analysis.put("title", title);
        analysis.put("contentLength", content.length());
        analysis.put("paragraphCount", countParagraphs(content));
        analysis.put("codeBlockCount", countOccurrences(content, "```"));
        analysis.put("imageCount", countOccurrences(content, "!["));
        analysis.put("summaryLength", String.valueOf(articleInfo.getOrDefault("summary", "")).length());

        List<String> issues = new ArrayList<>();
        if (content.length() < 200) {
            issues.add("正文过短（不足 200 字）");
        }
        if (title.isBlank()) {
            issues.add("缺少标题");
        }
        if (countParagraphs(content) < 3) {
            issues.add("段落结构单薄（不足 3 段）");
        }
        analysis.put("issues", issues);

        return analysis;
    }

    //读取用户长期记忆（失败兜底为空，不阻断流程）
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

    //以文章标题/内容为主题检索站内相关文章（失败兜底空列表）
    private List<Map<String, Object>> retrieveRagReferences(Map<String, Object> articleInfo) {
        try {
            String query = String.valueOf(articleInfo.getOrDefault("title", ""));
            AiIntent intent = new AiIntent();
            intent.setIntent("ARTICLE_SEARCH");
            intent.setKeyWord(query);

            ArticleRagSearchResult result = articleRagSearchService.search(query, intent);

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

    //LLM 生成优化方案（只生成方案，不生成最终文章）
    private String buildOptimizationPlan(
            AiWorkflowRun run,
            Map<String, Object> articleInfo,
            String instruction,
            String memoryContext,
            List<Map<String, Object>> ragReferences,
            AiWorkflowStepEmitter emitter
    ) {
        return buildOptimizationPlan(run, articleInfo, instruction, memoryContext, ragReferences, null, emitter);
    }

    private String buildOptimizationPlan(
            AiWorkflowRun run,
            Map<String, Object> articleInfo,
            String instruction,
            String memoryContext,
            List<Map<String, Object>> ragReferences,
            String feedback,
            AiWorkflowStepEmitter emitter
    ) {
        try {
            StringBuilder fullContent = new StringBuilder();
            TokenUsageAccumulator usage = new TokenUsageAccumulator();

            chatClientBuilder.build()
                    .prompt()
                    .options(OpenAiChatOptions.builder()
                            .maxTokens(2000)
                            .streamUsage(true)
                            .build())
                    .system("""
                        你是博客文章优化专家，只输出 Markdown。
                        不要输出解释，不要输出代码块包裹全文。
                        基于原文、用户要求、用户写作偏好和站内相关文章参考，输出一份具体的优化方案。
                        方案必须包含：
                        1. 整体评估（原文优点与主要问题）
                        2. 分项优化点（结构 / 内容 / 表达 / 技术深度）
                        3. 预期改动范围（保留原文主体，只改该改的）
                        只输出优化方案，不要重写文章正文。
                        事实边界：不得新增原文、用户要求、站内参考中没有出现的具体数字、业务规模、性能指标、成功率、公司/站点生产经验。
                        站内相关文章参考只用于补充技术背景，不能把参考文章中的业务数据、项目经验、结论当作当前文章事实。
                        """)
                    .user(buildPlanUserPrompt(articleInfo, instruction, memoryContext, ragReferences, feedback))
                    .stream()
                    .chatResponse()
                    .timeout(Duration.ofSeconds(60))
                    .doOnNext(response -> {
                        String chunk = response.getResult() == null ? "" : response.getResult().getOutput().getText();
                        if (chunk != null && !chunk.isEmpty()) {
                            fullContent.append(chunk);
                            emitter.emitContent(
                                    AiWorkflowStep.GENERATE_OPTIMIZATION_PLAN.name(),
                                    "optimizationPlan",
                                    chunk
                            );
                        }
                        usage.add(response.getMetadata().getUsage());
                    })
                    .blockLast();

            workflowTokenRecorder.accumulate(run, usage);

            String content = fullContent.toString();
            if (content.isBlank()) {
                throw new RuntimeException("优化方案生成失败：模型返回空内容");
            }

            return content;
        } catch (RuntimeException e) {
            //LLM 失败不静默兜底：抛给 runStep 记 FAILED，Service 层 markFailed → 前端可重试；
            //消息分类成友好文案，原始堆栈由 Service 层日志保留
            throw LlmErrorClassifier.wrap("优化方案生成失败：", e);
        } catch (Exception e) {
            throw new RuntimeException("优化方案生成失败：" + LlmErrorClassifier.friendlyMessage(e), e);
        }
    }

    //LLM 按方案重写文章（保留原文主体，只输出优化后的完整 Markdown 正文）
    private String rewriteArticleByLLM(AiWorkflowRun run, Map<String, Object> context, String feedback, AiWorkflowStepEmitter emitter) {
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
                        你是博客文章优化改写器。
                        严格按优化方案重写文章，只输出优化后的完整 Markdown 正文。
                        保留原文的技术细节、代码示例和作者风格，只优化结构与表达。
                        优化时优先保留原文二级标题结构和顺序。
                        不要把多个小节合并成单节，除非原文本来就只有一节。
                        不要输出解释，不要输出代码块包裹全文。
                        禁止输出优化方案、评估分析、分项标题。
                        最终输出的内容应该像一篇可以直接发布的博客文章。
                        每个二级标题下面必须有完整段落解释，不能只列要点。
                        事实边界：不得新增原文、用户要求、优化方案、站内参考中没有出现的具体数字、业务规模、性能指标、成功率、公司/站点生产经验。
                        如果需要补充案例，只能使用原文已有事实，或用“例如/假设场景”明确标注为示例，不能写成真实已发生结果。
                        不要把“站内相关文章参考”描述成“本站生产实践”。
                        站内相关文章参考只用于补充技术背景，不能把参考文章中的业务数据、项目经验、结论当作当前文章事实。
                        """)
                    .user(buildRewriteUserPrompt(context, feedback))
                    .stream()
                    .chatResponse()
                    .timeout(Duration.ofSeconds(60))
                    .doOnNext(response -> {
                        String chunk = response.getResult() == null ? "" : response.getResult().getOutput().getText();
                        if (chunk != null && !chunk.isEmpty()) {
                            fullContent.append(chunk);
                            emitter.emitContent(
                                    AiWorkflowStep.REWRITE_ARTICLE.name(),
                                    "optimizedContent",
                                    chunk
                            );
                        }
                        usage.add(response.getMetadata().getUsage());
                    })
                    .blockLast();

            workflowTokenRecorder.accumulate(run, usage);

            String markdown = fullContent.toString();
            if (markdown.isBlank()) {
                throw new RuntimeException("文章重写失败：模型返回空内容");
            }

            return markdown;
        } catch (RuntimeException e) {
            //LLM 失败不静默兜底：抛给 runStep 记 FAILED，Service 层 markFailed → 前端可重试；
            //消息分类成友好文案，原始堆栈由 Service 层日志保留
            throw LlmErrorClassifier.wrap("文章重写失败：", e);
        } catch (Exception e) {
            throw new RuntimeException("文章重写失败：" + LlmErrorClassifier.friendlyMessage(e), e);
        }
    }

    //规则质量检查：内容非空、长度不缩水、结构完整
    private Map<String, Object> buildContentCheck(String optimizedContent, Map<String, Object> context) {
        String original = String.valueOf(workflowContextSupport.getMap(workflowContextSupport.getStepResults(context), "article").getOrDefault("content", ""));

        List<String> issues = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();

        if (optimizedContent == null || optimizedContent.isBlank()) {
            issues.add("优化后内容为空");
        } else {
            if (optimizedContent.length() < original.length() * 0.5) {
                issues.add("优化后内容明显变短（不足原文一半），可能过度删减");
            }
            if (optimizedContent.length() < 200) {
                issues.add("优化后正文过短（不足 200 字）");
            }
            if (countParagraphs(optimizedContent) < 3) {
                issues.add("优化后段落结构单薄（不足 3 段）");
            }
            if (countOccurrences(optimizedContent, "```") > 0) {
                suggestions.add("代码块较多，注意代码缩进和语言标注");
            }
            // 大纲检测：输出像优化方案而非完整文章
            if (looksLikeOutline(optimizedContent)) {
                issues.add("优化后内容像大纲或要点列表，缺少完整段落展开，请重新重写");
            }
        }

        Map<String, Object> check = new HashMap<>();
        check.put("passed", issues.isEmpty());
        check.put("issues", issues);
        check.put("suggestions", suggestions);
        return check;
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

    //从优化稿构建 fillArticle 指令
    private AiEditorCommand buildEditorAction(Map<String, Object> context) {
        Map<String, Object> stepResults = workflowContextSupport.getStepResults(context);
        Map<String, Object> article = workflowContextSupport.getMap(stepResults, "article");
        String optimizedContent = String.valueOf(stepResults.getOrDefault("optimizedContent", ""));

        AiEditorCommand command = new AiEditorCommand();
        command.setType("fillArticle");
        command.setTitle(String.valueOf(article.getOrDefault("title", "")));
        command.setSummary(String.valueOf(article.getOrDefault("summary", "")));
        //不传 categoryName：前端仅在非空时设置分类，保留编辑器当前分类
        command.setContent(optimizedContent);

        // 把被优化的文章 ID 传回前端，让编辑器走 /editor/:id 编辑模式
        Object articleId = workflowContextSupport.getMap(context, "input").get("articleId");
        if (articleId instanceof Number) {
            command.setArticleId(((Number) articleId).longValue());
        }

        return command;
    }

    // ==================== prompt 构建 ====================

    private String buildPlanUserPrompt(
            Map<String, Object> articleInfo,
            String instruction,
            String memoryContext,
            List<Map<String, Object>> ragReferences,
            String feedback
    ) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("## 原文标题\n").append(articleInfo.getOrDefault("title", "")).append("\n\n");
        prompt.append("## 原文内容\n").append(articleInfo.getOrDefault("content", "")).append("\n\n");

        if (!workflowContextSupport.isBlank(instruction)) {
            prompt.append("## 用户优化要求\n").append(instruction).append("\n\n");
        }
        if (!workflowContextSupport.isBlank(feedback)) {
            prompt.append("## 用户修改意见\n").append(feedback).append("\n\n");
        }
        if (!workflowContextSupport.isBlank(memoryContext)) {
            prompt.append("## 用户写作偏好\n").append(memoryContext).append("\n\n");
        }
        if (ragReferences != null && !ragReferences.isEmpty()) {
            prompt.append("## 站内相关文章参考\n");
            for (Map<String, Object> ref : ragReferences) {
                prompt.append("- [").append(ref.get("title")).append("] ")
                        .append(ref.get("snippet")).append("\n");
            }
        }

        return prompt.toString();
    }

    private String buildRewriteUserPrompt(Map<String, Object> context, String feedback) {
        Map<String, Object> stepResults = workflowContextSupport.getStepResults(context);
        Map<String, Object> article = workflowContextSupport.getMap(stepResults, "article");

        StringBuilder prompt = new StringBuilder();
        prompt.append("## 原文标题\n").append(article.getOrDefault("title", "")).append("\n\n");
        prompt.append("## 原文内容\n").append(article.getOrDefault("content", "")).append("\n\n");
        prompt.append("## 优化方案\n").append(stepResults.getOrDefault("optimizationPlan", "")).append("\n\n");
        prompt.append("## 分析结果\n").append(workflowContextSupport.toJson(workflowContextSupport.getMap(stepResults, "analysis"))).append("\n\n");

        if (!workflowContextSupport.isBlank(feedback)) {
            prompt.append("## 用户修改意见\n").append(feedback).append("\n\n");
        }

        return prompt.toString();
    }

    // ==================== context 工具 ====================

    //通用 context 结构：input / memoryContext / ragContext / stepResults / feedbackHistory
    private Map<String, Object> buildInitialContext(AiWorkflowOptimizeArticleDTO dto) {
        Map<String, Object> context = new HashMap<>();
        context.put("workflowVersion", WORKFLOW_VERSION);

        Map<String, Object> input = new HashMap<>();
        input.put("articleId", dto.getArticleId());
        input.put("instruction", dto.getInstruction());
        context.put("input", input);

        context.put("memoryContext", "");
        context.put("ragContext", new HashMap<>());
        context.put("stepResults", new HashMap<>());
        context.put("feedbackHistory", new ArrayList<>());

        return context;
    }

    private Long getInputArticleId(Map<String, Object> context) {
        Object value = workflowContextSupport.getMap(context, "input").get("articleId");
        return value == null ? null : Long.valueOf(String.valueOf(value));
    }

    private String getInputInstruction(Map<String, Object> context) {
        return String.valueOf(workflowContextSupport.getMap(context, "input").getOrDefault("instruction", ""));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getRagReferences(Map<String, Object> context) {
        Object value = workflowContextSupport.getMap(context, "ragContext").get("references");
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return new ArrayList<>();
    }

    // ==================== 文本统计 ====================

    private int countParagraphs(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        return (int) content.lines().filter(line -> !line.isBlank()).count();
    }

    private int countOccurrences(String content, String target) {
        if (content == null || content.isBlank() || target == null || target.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(target, index)) != -1) {
            count++;
            index += target.length();
        }
        return count;
    }

    //校验文章
    private void validateArticleOwner(Long articleId, Long userId) {
        Articles article = articlesService.getById(articleId);
        if (article == null) {
            throw new IllegalArgumentException("文章不存在或已删除");
        }
        if (!userId.equals(article.getAuthorId())) {
            throw new IllegalArgumentException("只能优化自己的文章");
        }
    }
}
