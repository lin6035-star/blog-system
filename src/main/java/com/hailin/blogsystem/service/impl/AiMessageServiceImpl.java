package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.ai.planner.AgentPlannerSupport;
import com.hailin.blogsystem.ai.trace.AgentDecisionTrace;
import com.hailin.blogsystem.ai.trace.AgentDecisionTraceSink;
import com.hailin.blogsystem.ai.workflow.CreateArticleWorkflowHandler;
import com.hailin.blogsystem.ai.workflow.WorkflowContextSupport;
import com.hailin.blogsystem.ai.workflow.WorkflowHandlerRegistry;
import com.hailin.blogsystem.entity.dto.AiWorkflowLearningPlanDTO;
import com.hailin.blogsystem.ai.rag.ArticleRagPromptBuilder;
import com.hailin.blogsystem.ai.rag.ArticleRagSearchService;
import com.hailin.blogsystem.ai.tool.AiToolActionRegistry;
import com.hailin.blogsystem.ai.tool.AiUserProfileTools;
import com.hailin.blogsystem.ai.workflow.AiWorkflowStepEmitter;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.*;
import com.hailin.blogsystem.entity.dto.*;
import com.hailin.blogsystem.entity.vo.*;
import com.hailin.blogsystem.mapper.AiMessageMapper;
import com.hailin.blogsystem.mapper.AiSessionMapper;
import com.hailin.blogsystem.mapper.AiWorkflowRunMapper;
import com.hailin.blogsystem.mapper.AiWorkflowStepLogMapper;
import com.hailin.blogsystem.service.*;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiMessageServiceImpl extends ServiceImpl<AiMessageMapper, AiMessages>
        implements AiMessageService {

    private final AiSessionMapper aiSessionMapper;
    private final AiModelService aiModelService;
    private final AiPromptService aiPromptService;
    private final AiToolActionRegistry aiToolActionRegistry;
    private final AiUserProfileTools aiUserProfileTools;
    private final AiIntentClassifier aiIntentClassifier;
    private final AiEditorDraftGenerator aiEditorDraftGenerator;
    private final ArticlesService articlesService;
    private final ArticleRagPromptBuilder articleRagPromptBuilder;
    private final ArticleRagSearchService articleRagSearchService;
    private final AiMemoryCandidateExtractorService aiMemoryCandidateExtractorService;
    private final AiWorkflowRunService aiWorkflowRunService;
    private final AiWorkflowRunMapper aiWorkflowRunMapper;
    private final AiWorkflowStepLogMapper aiWorkflowStepLogMapper;
    private final CreateArticleWorkflowHandler createArticleWorkflowHandler;
    private final WorkflowContextSupport workflowContextSupport;
    private final WorkflowHandlerRegistry workflowHandlerRegistry;
    private final AiEpisodicMemoryExtractorService aiEpisodicMemoryExtractorService;
    private final AiConversationSummaryService aiConversationSummaryService;
    private final AiSessionService aiSessionService;
    private final ObjectMapper objectMapper;
    private final LearningPlansService learningPlansService;
    private final AgentPlannerSupport agentPlannerSupport;
    private final AgentDecisionTraceSink agentDecisionTraceSink;

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    @Override  //1.查询某个会话的消息列表
    public List<AiMessageVO> getMessages(String id) {
        Long userId = UserContext.get();
        Long sessionId = Long.valueOf(id);

        AiSessions aiSessions = aiSessionMapper.selectById(sessionId);

        if(aiSessions == null){
            throw new IllegalArgumentException("会话不存在");
        }

        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }

        if(!aiSessions.getUserId().equals(userId)){
            throw new IllegalArgumentException("会话不属于该用户");
        }

        List<AiMessages> list = lambdaQuery()
                .eq(AiMessages::getSessionId, sessionId)
                .orderByAsc(AiMessages::getCreatedAt).list();

        List<AiMessageVO> aiMessageVOList = list.stream()
                .map(AiMessageVO::from)
                .toList();

        return aiMessageVOList;
    }

    private AiSessions getOwnedSession(Long sessionId, Long userId) {
        AiSessions session = aiSessionService.lambdaQuery()
                .eq(AiSessions::getId, sessionId)
                .eq(AiSessions::getUserId, userId)
                .one();

        if (session == null) {
            throw new IllegalArgumentException("会话不存在");
        }

        return session;
    }
    private String toJson(Object object) {
        if (object == null) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("页面上下文格式错误");
        }
    }
    //新会话标题取第一个问题的前二十字为标题
    private String buildSessionTitle(String message) {
        if (message == null || message.isBlank()) {
            return "新对话";
        }

        String title = message.trim();

        if (title.length() > 20) {
            return title.substring(0, 20);
        }

        return title;
    }
    //游客调用方法（不保存数据库）
    /*
    组装临时 userMessage / assistantMessage / session 返回
    不 save(userMessage)
    不 save(assistantMessage)
    不 createSession()
    不 update ai_sessions
    */


    @Override  //流式输出
    public Flux<AiChatEventVO> streamChat(AiChatDTO aiChatDTO) {
        if (aiChatDTO == null) {
            throw new IllegalArgumentException("请求参数不能为空");
        }

        String message = aiChatDTO.getMessage() == null ? "" : aiChatDTO.getMessage().trim();
        if (message.isEmpty()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }

        Long userId = UserContext.get();
        String rawPageContextJson = toJson(aiChatDTO.getPageContext());

        if (userId == null) {
            return streamGuestChat(message, aiChatDTO.getPageContext());
        }

        return streamUserChat(aiChatDTO, message, aiChatDTO.getPageContext(), rawPageContextJson, userId);
    }
    //登录用户流式逻辑
    private Flux<AiChatEventVO> streamUserChat(
            AiChatDTO aiChatDTO,
            String message,
            PageContextDTO pageContext,
            String rawPageContextJson,
            Long userId
    ) {
        Long sessionId;
        AtomicBoolean assistantSaved = new AtomicBoolean(false);

        if (aiChatDTO.getSessionId() == null || aiChatDTO.getSessionId().trim().isEmpty()) {
            AiCreateSessionDTO createSessionDTO = new AiCreateSessionDTO();
            createSessionDTO.setTitle(buildSessionTitle(message));

            AiSessionVO createdSession = aiSessionService.createSession(createSessionDTO);
            sessionId = Long.valueOf(createdSession.getId());
        } else {
            sessionId = Long.valueOf(aiChatDTO.getSessionId());
            getOwnedSession(sessionId, userId);
        }

        AiSessions session = getOwnedSession(sessionId,userId);

        // requestId 提前生成：Planner 决策 trace 与后续模型调用共用同一个 id，串成一条可追溯链路
        String requestId = UUID.randomUUID().toString();

        if(session.getActiveWorkflowRunId() != null){
            return streamContinueActiveWorkflow(
                    message,
                    pageContext,
                    rawPageContextJson,
                    userId,
                    session
            );
        }

        // 统一使用 LLM 分类结果 + Agent Planner 决策。
        // 这一轮开始，文章创作、文章优化、学习 Workflow
        // 都先经过同一个 AgentDecision。
        AiIntent intent = aiIntentClassifier.classify(message, pageContext);

        AgentDecision routeDecision = agentPlannerSupport.decide(
                message,
                intent,
                pageContext,
                userId,
                session
        );

        // 所有非 active Workflow 的决策都记录。
        // active Workflow 在前面已经优先直通，不进入这里。
        recordAgentDecisionTrace(
                requestId,
                userId,
                sessionId,
                message,
                intent,
                routeDecision
        );

        // CTA 不创建 Workflow，不绑定 activeWorkflowRunId。
        if (routeDecision.getAction() == AgentAction.CTA) {
            return streamCtaMessage(
                    sessionId,
                    userId,
                    rawPageContextJson,
                    message,
                    routeDecision
            );
        }

        // 统一处理 Workflow。
        if (routeDecision.getAction() == AgentAction.WORKFLOW) {

            // 文章创作 Workflow
            if (routeDecision.isWorkflow(AiWorkflowType.CREATE_ARTICLE)) {
                return streamCreateArticleWorkflowFromIntent(
                        message,
                        intent,
                        pageContext,
                        rawPageContextJson,
                        userId,
                        sessionId
                );
            }

            // 文章优化 Workflow
            if (routeDecision.isWorkflow(AiWorkflowType.OPTIMIZE_ARTICLE)) {
                return streamArticleOptimizeWorkflowFromIntent(
                        message,
                        intent,
                        pageContext,
                        rawPageContextJson,
                        userId,
                        sessionId
                );
            }

            // 学习难点攻坚 Workflow
            if (routeDecision.isWorkflow(AiWorkflowType.LEARNING_ASSIST)) {
                Optional<Flux<AiChatEventVO>> routed =
                        routeLearningAssistWorkflow(
                                message,
                                intent == null ? null : intent.getLearningPlanRef(),
                                pageContext,
                                rawPageContextJson,
                                userId,
                                sessionId,
                                requestId
                        );

                if (routed.isPresent()) {
                    return routed.get();
                }

                // 计划定位失败时降级 CTA。
                return streamCtaMessage(
                        sessionId,
                        userId,
                        rawPageContextJson,
                        message,
                        routeDecision
                );
            }

            // 学习进度调整 Workflow
            if (routeDecision.isWorkflow(AiWorkflowType.LEARNING_PROGRESS)) {
                Optional<Flux<AiChatEventVO>> routed =
                        routeLearningProgressWorkflow(
                                message,
                                intent == null ? null : intent.getLearningPlanRef(),
                                pageContext,
                                rawPageContextJson,
                                userId,
                                sessionId,
                                requestId
                        );

                if (routed.isPresent()) {
                    return routed.get();
                }

                // 没有可调整的 ACTIVE 计划时，
                // 沿用当前行为：进入学习规划 Workflow 创建新计划。
                return streamLearningPlanWorkflowFromIntent(
                        message,
                        intent,
                        pageContext,
                        rawPageContextJson,
                        userId,
                        sessionId,
                        requestId
                );
            }

            // 学习规划 Workflow
            if (routeDecision.isWorkflow(AiWorkflowType.LEARNING_PLAN)) {
                return streamLearningPlanWorkflowFromIntent(
                        message,
                        intent,
                        pageContext,
                        rawPageContextJson,
                        userId,
                        sessionId,
                        requestId
                );
            }
        }


        AiArticleActionCommand articleActionFromIntent = buildArticleActionFromDecision(routeDecision, intent, pageContext);
        String extraPromptContext = buildExtraPromptContextFromIntent(intent, pageContext);

        /*
         * 是否读取当前文章，由 Planner 的 retrievalMode 决定。
         * 这里的 intent 只负责提供 articleId 等结构化数据。
         */
        if ((extraPromptContext == null || extraPromptContext.isBlank())
                && routeDecision.usesRetrieval("CURRENT_ARTICLE")) {
            extraPromptContext = buildArticleDetailContextFromIntent(intent, pageContext);
        }

        AiNavigateCommand navigateFromIntent = buildNavigateFromDecision(routeDecision, intent, pageContext);
        AiEditorCommand editorActionFromIntent = buildEditorActionFromDecision(routeDecision, intent);

        // 拼完整 prompt（含历史记忆 + 页面上下文 + 当前问题）
        AiPrompt prompt = aiPromptService.buildPrompt(message, pageContext, sessionId);
        prompt.setArticleToolsEnabled(shouldEnableArticleTools(routeDecision));
        prompt.setSessionId(sessionId);
        prompt.setLearningDashboardToolEnabled(routeDecision.isTool("getLearningDashboard"));

        appendExtraPromptContext(prompt,extraPromptContext);  //将第一次模型回复的拼进prompt

        String actionResultPromptContext = buildActionResultPromptContext(navigateFromIntent);
        appendExtraPromptContext(prompt,actionResultPromptContext);

        boolean currentArticleRetrieval = routeDecision.usesRetrieval("CURRENT_ARTICLE");
        boolean articleSearchRetrieval = routeDecision.usesRetrieval("ARTICLE_SEARCH");

        ArticleRagContext currentArticleReference = buildCurrentArticleReference(routeDecision, intent, pageContext);

        List<ArticleRagContext> ragContexts;

        if (currentArticleRetrieval) {
            ragContexts = currentArticleReference == null ? List.of() : List.of(currentArticleReference);
        } else if (articleSearchRetrieval) {
            ArticleRagSearchResult ragSearchResult = articleRagSearchService.search(message, intent);
            ragContexts = ragSearchResult.contexts();

            appendRagContextToPrompt(prompt, ragContexts);
        } else {
            ragContexts = List.of();
        }

        AiMessages userMessage = new AiMessages();
        userMessage.setSessionId(sessionId);
        userMessage.setRole(ROLE_USER);
        userMessage.setContent(message);
        userMessage.setPageContext(rawPageContextJson);
        userMessage.setCreatedAt(LocalDateTime.now());
        save(userMessage);

        StringBuilder fullReply = new StringBuilder();

        AiChatEventVO paramEvent = AiChatEventVO.builder()
                .eventType(AiChatEventType.PARAM.getValue())
                .eventData(Map.of(
                        "session", AiSessionVO.from(session),
                        "userMessage", AiMessageVO.from(userMessage)
                ))
                .build();

        TokenUsageAccumulator usage = new TokenUsageAccumulator();
        Flux<AiChatEventVO> dataEvents = aiModelService.streamChat(prompt,requestId,usage)
                .doOnNext(fullReply::append)
                .map(chunk -> AiChatEventVO.builder()
                        .eventType(AiChatEventType.DATA.getValue())
                        .eventData(chunk)
                        .build());

        Mono<AiChatEventVO> stopEvent = Mono.fromSupplier(() -> {
            AiMessages assistantMessage = saveStreamAssistantMessage(sessionId,userId,rawPageContextJson, fullReply.toString());
            //token 用量落库（含工具调用多轮累计）
            if (usage.getTotalTokens() > 0) {
                assistantMessage.setTokenCount((long) usage.getTotalTokens());
                updateById(assistantMessage);
            }

            // 异步提取候选记忆（规则预筛命中才写入，不会阻塞 SSE）
            aiMemoryCandidateExtractorService.extractAfterChat(
                    userId,
                    sessionId,
                    userMessage.getId(),
                    message,
                    fullReply.toString()
            );

            // 异步提取情景记忆：记录重要事件、决策、里程碑和计划，不阻塞 SSE
            aiEpisodicMemoryExtractorService.extractAfterChat(
                    userId,
                    sessionId,
                    userMessage.getId(),
                    assistantMessage.getId(),
                    message,
                    fullReply.toString()
            );

            //会话压缩
            aiConversationSummaryService.compressAfterChat(userId, sessionId);

            AiSessions updatedSession = getOwnedSession(sessionId, userId);

            Map<String, Object> eventData = new HashMap<>();
            eventData.put("session", AiSessionVO.from(updatedSession));
            eventData.put("assistantMessage", AiMessageVO.from(assistantMessage));
            eventData.put("references",toRagReferences(ragContexts));

            // 意图分类器的 navigateFromIntent 可能缺少 param（如用户说"跳转到那篇文章"），
            // 此时降级走 Tool Calling 路径，主模型有对话历史可以知道具体是哪个 ID。
            AiNavigateCommand navigate = navigateFromIntent;
            if (isNavigateMissingRequiredParam(navigate)) {
                navigate = null;
            }
            if (navigate == null) {
                navigate = getNavigateFromToolRegistry(requestId, routeDecision, intent);
            }
            if (navigate != null) {
                eventData.put("navigate", navigate);
            }

            AiEditorCommand editorAction = editorActionFromIntent;

            if (editorAction == null) {
                editorAction = getEditorActionFromToolRegistry(requestId, routeDecision);
            }
            // fillArticle 只能从 Workflow 的 editorAction 出来，普通聊天不允许直接填充文章
            if (editorAction != null && "fillArticle".equals(editorAction.getType())) {
                editorAction = null;
            }
            if(editorAction != null){
                eventData.put("editorAction", editorAction);
            }

            AiArticleActionCommand articleAction = articleActionFromIntent;
            // 是否允许读取 Tool Registry 由 getArticleActionFromToolRegistry 自己判断
            if (articleAction == null) {
                articleAction = getArticleActionFromToolRegistry(
                        requestId,
                        routeDecision
                );
            }
            if (articleAction != null) {
                eventData.put("articleAction", articleAction);
            }

            return AiChatEventVO.builder()
                    .eventType(AiChatEventType.STOP.getValue())
                    .eventData(eventData)
                    .build();
        });

        return Flux.concat(
                Flux.just(paramEvent),
                dataEvents,
                stopEvent
        ).doFinally(signalType -> {
            if(signalType == SignalType.CANCEL
            && fullReply.length() > 0
            && assistantSaved.compareAndSet(false,true)){
                saveStreamAssistantMessage(
                        sessionId,
                        userId,
                        rawPageContextJson,
                        fullReply.toString()
                );
            }
            aiToolActionRegistry.clear(requestId);
        })
                ;
    }

    /** 文章创作意图但没有明确主题 → 追问主题，不起 Workflow */
    private Flux<AiChatEventVO> streamAskArticleTopic(
            String message,
            String rawPageContextJson,
            Long userId,
            Long sessionId
    ) {
        AiMessages userMessage = new AiMessages();
        userMessage.setSessionId(sessionId);
        userMessage.setRole(ROLE_USER);
        userMessage.setContent(message);
        userMessage.setPageContext(rawPageContextJson);
        userMessage.setCreatedAt(LocalDateTime.now());
        save(userMessage);

        String reply = "可以，想写什么主题？\n比如 Redis 缓存、Kafka 消息队列、RAG 检索增强这些";

        AiMessages assistantMessage = saveStreamAssistantMessage(
                sessionId,
                userId,
                rawPageContextJson,
                reply
        );

        AiSessions updatedSession = getOwnedSession(sessionId, userId);

        AiChatEventVO paramEvent = AiChatEventVO.builder()
                .eventType(AiChatEventType.PARAM.getValue())
                .eventData(Map.of(
                        "session", AiSessionVO.from(updatedSession),
                        "userMessage", AiMessageVO.from(userMessage)
                ))
                .build();

        Map<String, Object> eventData = new HashMap<>();
        eventData.put("session", AiSessionVO.from(updatedSession));
        eventData.put("assistantMessage", AiMessageVO.from(assistantMessage));
        eventData.put("references", List.of());

        AiChatEventVO stopEvent = AiChatEventVO.builder()
                .eventType(AiChatEventType.STOP.getValue())
                .eventData(eventData)
                .build();

        return Flux.just(paramEvent, stopEvent);
    }

    /** 从意图分类结果创建文章优化 Workflow */
    private Flux<AiChatEventVO> streamArticleOptimizeWorkflowFromIntent(
            String message,
            AiIntent intent,
            PageContextDTO pageContext,
            String rawPageContextJson,
            Long userId,
            Long sessionId
    ) {
        AiMessages userMessage = new AiMessages();
        userMessage.setSessionId(sessionId);
        userMessage.setRole(ROLE_USER);
        userMessage.setContent(message);
        userMessage.setPageContext(rawPageContextJson);
        userMessage.setCreatedAt(LocalDateTime.now());
        save(userMessage);

        AiWorkflowOptimizeArticleDTO workflowDTO = new AiWorkflowOptimizeArticleDTO();
        workflowDTO.setConversationId(sessionId);
        workflowDTO.setArticleId(Long.valueOf(pageContext.getArticleId()));
        workflowDTO.setInstruction(message);
        workflowDTO.setPageContext(pageContext);

        AiChatEventVO paramEvent = AiChatEventVO.builder()
                .eventType(AiChatEventType.PARAM.getValue())
                .eventData(Map.of(
                        "session", AiSessionVO.from(getOwnedSession(sessionId, userId)),
                        "userMessage", AiMessageVO.from(userMessage)
                ))
                .build();

        Flux<AiChatEventVO> workflowEvents = Flux.create(sink ->
                Schedulers.boundedElastic().schedule(() -> {
                    UserContext.set(userId);
                    try {
                        AtomicReference<Long> workflowRunId = new AtomicReference<>();
                        AiWorkflowStepEmitter emitter = new AiWorkflowStepEmitter() {
                            @Override
                            public void bindWorkflowRunId(Long runId) {
                                workflowRunId.set(runId);
                            }

                            @Override
                            public void emit(String step, String status, String stepMessage) {
                                Long runId = workflowRunId.get();
                                if (runId == null) {
                                    return;
                                }
                                Map<String, Object> eventData = new HashMap<>();
                                eventData.put("workflowRunId", String.valueOf(runId));
                                eventData.put("workflowType", AiWorkflowType.OPTIMIZE_ARTICLE.name());
                                eventData.put("step", step);
                                eventData.put("status", status);
                                eventData.put("message", stepMessage);
                                sink.next(AiChatEventVO.builder()
                                        .eventType(AiChatEventType.WORKFLOW_STEP.getValue())
                                        .eventData(eventData)
                                        .build());
                            }

                            @Override
                            public void emitContent(String step, String field, String delta) {
                                if ("optimizationPlan".equals(field) || "optimizedContent".equals(field)) {
                                    Long runId = workflowRunId.get();
                                    if (runId == null) {
                                        return;
                                    }
                                    Map<String, Object> eventData = new HashMap<>();
                                    eventData.put("workflowRunId", String.valueOf(runId));
                                    eventData.put("step", step);
                                    eventData.put("field", field);
                                    eventData.put("delta", delta);
                                    sink.next(AiChatEventVO.builder()
                                            .eventType(AiChatEventType.WORKFLOW_CONTENT_DELTA.getValue())
                                            .eventData(eventData)
                                            .build());
                                }
                            }
                        };

                        AiWorkflowRunVO workflow = aiWorkflowRunService.createArticleOptimizeWorkflow(workflowDTO, emitter);

                        AiMessages assistantMessage = saveStreamAssistantMessage(
                                sessionId,
                                userId,
                                rawPageContextJson,
                                buildOptimizeWorkflowAssistantContent(workflow),
                                Long.valueOf(workflow.getId())
                        );

                        AiSessions updatedSession = getOwnedSession(sessionId, userId);

                        Map<String, Object> eventData = new HashMap<>();
                        eventData.put("session", AiSessionVO.from(updatedSession));
                        eventData.put("assistantMessage", AiMessageVO.from(assistantMessage));
                        eventData.put("references", List.of());
                        eventData.put("workflow", workflow);

                        sink.next(AiChatEventVO.builder()
                                .eventType(AiChatEventType.STOP.getValue())
                                .eventData(eventData)
                                .build());

                        sink.complete();
                    } catch (Throwable e) {
                        emitRejectedOrError(sink, sessionId, userId, rawPageContextJson,
                                AiWorkflowType.OPTIMIZE_ARTICLE.name(), e);
                    } finally {
                        UserContext.clear();
                    }
                })
        );

        return Flux.concat(Flux.just(paramEvent), workflowEvents);
    }

    private String buildOptimizeWorkflowAssistantContent(AiWorkflowRunVO workflow) {
        Object contextValue = workflow == null ? null : workflow.getContext();
        if (!(contextValue instanceof Map<?, ?> context)) {
            return "已创建文章优化 Workflow，请先确认优化方案。";
        }

        Object stepResultsValue = context.get("stepResults");
        if (!(stepResultsValue instanceof Map<?, ?> stepResults)) {
            return "已创建文章优化 Workflow，请先确认优化方案。";
        }

        Object plan = stepResults.get("optimizationPlan");
        if (plan == null || String.valueOf(plan).isBlank()) {
            return "已创建文章优化 Workflow，请先确认优化方案。";
        }

        return "已创建文章优化 Workflow，请先确认下面的优化方案。\n\n" + plan;
    }

    /** 优化 Workflow 的可预期业务拒绝 → 返回友好的 AI 回复文案；非预期异常返回 null 交给 sink.error */
    /**
     * Workflow 入口统一异常收口：可预期业务拒绝 → 友好 AI 回复 + STOP 正常收尾；
     * 真异常（无友好回复）→ 原样 sink.error。拒绝文案由各 Workflow Handler 的
     * buildRejectedMessage 提供（业务规则归 Handler）。
     */
    private void emitRejectedOrError(
            FluxSink<AiChatEventVO> sink,
            Long sessionId,
            Long userId,
            String rawPageContextJson,
            String workflowType,
            Throwable e
    ) {
        String friendly = workflowHandlerRegistry.get(workflowType).buildRejectedMessage(e);
        if (friendly == null) {
            sink.error(e);
            return;
        }

        AiMessages assistantMessage = saveStreamAssistantMessage(
                sessionId,
                userId,
                rawPageContextJson,
                friendly,
                null
        );

        AiSessions updatedSession = getOwnedSession(sessionId, userId);

        Map<String, Object> eventData = new HashMap<>();
        eventData.put("session", AiSessionVO.from(updatedSession));
        eventData.put("assistantMessage", AiMessageVO.from(assistantMessage));
        eventData.put("references", List.of());

        sink.next(AiChatEventVO.builder()
                .eventType(AiChatEventType.STOP.getValue())
                .eventData(eventData)
                .build());

        sink.complete();
    }

    private Optional<Flux<AiChatEventVO>> routeLearningAssistWorkflow(
            String message,
            String learningPlanRef,
            PageContextDTO pageContext,
            String rawPageContextJson,
            Long userId,
            Long sessionId,
            String requestId
    ) {
        List<LearningPlans> mentioned = resolveMentionedActivePlans(userId, learningPlanRef, message);
        if (mentioned.size() == 1) {
            return Optional.of(streamLearningAssistWorkflowFromIntent(
                    message,
                    mentioned.get(0),
                    List.of(),
                    pageContext,
                    rawPageContextJson,
                    userId,
                    sessionId,
                    requestId
            ));
        }

        List<LearningPlans> actives = activeLearningPlans(userId);
        if (actives.size() == 1) {
            return Optional.of(streamLearningAssistWorkflowFromIntent(
                    message,
                    actives.get(0),
                    List.of(),
                    pageContext,
                    rawPageContextJson,
                    userId,
                    sessionId,
                    requestId
            ));
        }
        if (actives.size() > 1) {
            List<AiWorkflowLearningAssistDTO.Candidate> candidates = actives.stream()
                    .map(plan -> new AiWorkflowLearningAssistDTO.Candidate(plan.getId(), plan.getTitle()))
                    .toList();
            return Optional.of(streamLearningAssistWorkflowFromIntent(
                    message,
                    null,
                    candidates,
                    pageContext,
                    rawPageContextJson,
                    userId,
                    sessionId,
                    requestId
            ));
        }

        //没有 ACTIVE 计划时，攻坚请求继续走普通聊天，不自动创建新计划。
        return Optional.empty();
    }

    private Optional<Flux<AiChatEventVO>> routeLearningProgressWorkflow(
            String message,
            String learningPlanRef,
            PageContextDTO pageContext,
            String rawPageContextJson,
            Long userId,
            Long sessionId,
            String requestId
    ) {
        List<LearningPlans> mentioned = resolveMentionedActivePlans(userId, learningPlanRef, message);
        if (mentioned.size() == 1) {
            return Optional.of(streamLearningProgressWorkflowFromIntent(
                    message,
                    mentioned.get(0),
                    List.of(),
                    pageContext,
                    rawPageContextJson,
                    userId,
                    sessionId,
                    requestId
            ));
        }

        List<LearningPlans> actives = activeLearningPlans(userId);
        if (actives.size() == 1) {
            return Optional.of(streamLearningProgressWorkflowFromIntent(
                    message,
                    actives.get(0),
                    List.of(),
                    pageContext,
                    rawPageContextJson,
                    userId,
                    sessionId,
                    requestId
            ));
        }
        if (actives.size() > 1) {
            List<AiWorkflowLearningProgressDTO.Candidate> candidates = actives.stream()
                    .map(plan -> new AiWorkflowLearningProgressDTO.Candidate(plan.getId(), plan.getTitle()))
                    .toList();
            return Optional.of(streamLearningProgressWorkflowFromIntent(
                    message,
                    null,
                    candidates,
                    pageContext,
                    rawPageContextJson,
                    userId,
                    sessionId,
                    requestId
            ));
        }

        //没有 ACTIVE 计划时，保留原行为：调用方继续走新建学习计划流程。
        return Optional.empty();
    }

    private List<LearningPlans> resolveMentionedActivePlans(Long userId, String learningPlanRef, String message) {
        String reference = learningPlanRef == null || learningPlanRef.isBlank()
                ? message
                : learningPlanRef;
        List<LearningPlans> mentioned = learningPlansService.matchActivePlansByMessage(userId, reference);
        if (mentioned.isEmpty() && !Objects.equals(reference, message)) {
            return learningPlansService.matchActivePlansByMessage(userId, message);
        }
        return mentioned;
    }

    private List<LearningPlans> activeLearningPlans(Long userId) {
        return learningPlansService.listByUser(userId).stream()
                .filter(plan -> LearningPlans.STATUS_ACTIVE.equals(plan.getStatus()))
                .toList();
    }

    private Flux<AiChatEventVO> streamCreateArticleWorkflowFromIntent(
            String message,
            AiIntent intent,
            PageContextDTO pageContext,
            String rawPageContextJson,
            Long userId,
            Long sessionId
    ) {
        AiMessages userMessage = new AiMessages();
        userMessage.setSessionId(sessionId);
        userMessage.setRole(ROLE_USER);
        userMessage.setContent(message);
        userMessage.setPageContext(rawPageContextJson);
        userMessage.setCreatedAt(LocalDateTime.now());
        save(userMessage);

        // requirement 只用原始 message：intent.topic / requirements 是 LLM 结构化输出，会幻觉。
        // 主题是否明确交给 CreateArticleWorkflowHandler.isRequirementUnclear 确定性规则判断（LLM 判断意图，规则判断参数）
        String requirement = message;

        AiWorkflowCreateArticleDTO workflowDTO = new AiWorkflowCreateArticleDTO();
        workflowDTO.setConversationId(sessionId);
        workflowDTO.setRequirement(requirement);

        String topicEvidence = intent == null ? null : intent.getTopicEvidence();

        // 兼容旧版本分类器：没有 topicEvidence 时暂时使用 topic，
        // 但后续仍然由 Workflow 后端验证它是否来自用户原话。
        if (topicEvidence == null || topicEvidence.isBlank()) {
            topicEvidence = intent == null ? null : intent.getTopic();
        }

        workflowDTO.setTopicEvidence(topicEvidence);
        workflowDTO.setPageContext(pageContext);

        AiChatEventVO paramEvent = AiChatEventVO.builder()
                .eventType(AiChatEventType.PARAM.getValue())
                .eventData(Map.of(
                        "session", AiSessionVO.from(getOwnedSession(sessionId, userId)),
                        "userMessage", AiMessageVO.from(userMessage)
                ))
                .build();

        Flux<AiChatEventVO> workflowEvents = Flux.create(sink ->
                Schedulers.boundedElastic().schedule(() -> {
                    UserContext.set(userId);
                    try {
                        AtomicReference<Long> workflowRunId = new AtomicReference<>();
                        AiWorkflowStepEmitter emitter = new AiWorkflowStepEmitter() {
                            @Override
                            public void bindWorkflowRunId(Long runId) {
                                workflowRunId.set(runId);
                            }

                            @Override
                            public void emit(String step, String status, String stepMessage) {
                                Long runId = workflowRunId.get();
                                if (runId == null) {
                                    return;
                                }
                                Map<String, Object> eventData = new HashMap<>();
                                eventData.put("workflowRunId", String.valueOf(runId));
                                eventData.put("workflowType", AiWorkflowType.CREATE_ARTICLE.name());
                                eventData.put("step", step);
                                eventData.put("status", status);
                                eventData.put("message", stepMessage);
                                sink.next(AiChatEventVO.builder()
                                        .eventType(AiChatEventType.WORKFLOW_STEP.getValue())
                                        .eventData(eventData)
                                        .build());
                            }

                            @Override
                            public void emitContent(String step, String field, String delta) {
                                if ("outline".equals(field)) {
                                    Long runId = workflowRunId.get();
                                    if (runId == null) {
                                        return;
                                    }
                                    Map<String, Object> eventData = new HashMap<>();
                                    eventData.put("workflowRunId", String.valueOf(runId));
                                    eventData.put("step", step);
                                    eventData.put("field", field);
                                    eventData.put("delta", delta);
                                    sink.next(AiChatEventVO.builder()
                                            .eventType(AiChatEventType.WORKFLOW_CONTENT_DELTA.getValue())
                                            .eventData(eventData)
                                            .build());
                                }
                            }
                        };

                        // ServiceImpl 的 create 内部会绑定 session 的 activeWorkflowRunId
                        AiWorkflowRunVO workflow = aiWorkflowRunService.createArticleWorkflow(workflowDTO, emitter);

                        AiMessages assistantMessage = saveStreamAssistantMessage(
                                sessionId,
                                userId,
                                rawPageContextJson,
                                buildWorkflowAssistantContent(workflow),
                                Long.valueOf(workflow.getId())
                        );

                        AiSessions updatedSession = getOwnedSession(sessionId, userId);

                        Map<String, Object> eventData = new HashMap<>();
                        eventData.put("session", AiSessionVO.from(updatedSession));
                        eventData.put("assistantMessage", AiMessageVO.from(assistantMessage));
                        eventData.put("references", List.of());
                        eventData.put("workflow", workflow);

                        sink.next(AiChatEventVO.builder()
                                .eventType(AiChatEventType.STOP.getValue())
                                .eventData(eventData)
                                .build());

                        sink.complete();
                    } catch (Throwable e) {
                        emitRejectedOrError(sink, sessionId, userId, rawPageContextJson,
                                AiWorkflowType.CREATE_ARTICLE.name(), e);
                    } finally {
                        UserContext.clear();
                    }
                })
        );

        return Flux.concat(Flux.just(paramEvent), workflowEvents);
    }

    //学习规划 Workflow 创建入口：goal 只用原始 message（不信任 intent 结构化字段，topic 幻觉教训）
    private Flux<AiChatEventVO> streamLearningPlanWorkflowFromIntent(
            String message,
            AiIntent intent,
            PageContextDTO pageContext,
            String rawPageContextJson,
            Long userId,
            Long sessionId,
            String requestId
    ) {
        AiMessages userMessage = new AiMessages();
        userMessage.setSessionId(sessionId);
        userMessage.setRole(ROLE_USER);
        userMessage.setContent(message);
        userMessage.setPageContext(rawPageContextJson);
        userMessage.setCreatedAt(LocalDateTime.now());
        save(userMessage);

        AiWorkflowLearningPlanDTO workflowDTO = new AiWorkflowLearningPlanDTO();
        workflowDTO.setConversationId(sessionId);
        workflowDTO.setGoal(message);

        AiChatEventVO paramEvent = AiChatEventVO.builder()
                .eventType(AiChatEventType.PARAM.getValue())
                .eventData(Map.of(
                        "session", AiSessionVO.from(getOwnedSession(sessionId, userId)),
                        "userMessage", AiMessageVO.from(userMessage)
                ))
                .build();

        Flux<AiChatEventVO> workflowEvents = Flux.create(sink ->
                Schedulers.boundedElastic().schedule(() -> {
                    UserContext.set(userId);
                    try {
                        AiWorkflowStepEmitter emitter = new AiWorkflowStepEmitter() {
                            @Override
                            public void emit(String step, String status, String stepMessage) {
                                // 初次创建时 step 事件可以先不推给前端
                            }

                            @Override
                            public void emitContent(String step, String field, String delta) {
                                // 计划 JSON 不流式推前端，确认面板从 workflow 数据渲染
                            }
                        };

                        // ServiceImpl 的 create 内部会绑定 session 的 activeWorkflowRunId
                        // agentAutoStarted=true：这是 Planner 自动拉起的入口，run context 落标记供每会话上限统计
                        AiWorkflowRunVO workflow = aiWorkflowRunService.createLearningPlanWorkflow(workflowDTO, emitter, true, requestId);

                        AiMessages assistantMessage = saveStreamAssistantMessage(
                                sessionId,
                                userId,
                                rawPageContextJson,
                                buildLearningPlanAssistantContent(workflow),
                                Long.valueOf(workflow.getId())
                        );

                        AiSessions updatedSession = getOwnedSession(sessionId, userId);

                        Map<String, Object> eventData = new HashMap<>();
                        eventData.put("session", AiSessionVO.from(updatedSession));
                        eventData.put("assistantMessage", AiMessageVO.from(assistantMessage));
                        eventData.put("references", List.of());
                        eventData.put("workflow", workflow);

                        sink.next(AiChatEventVO.builder()
                                .eventType(AiChatEventType.STOP.getValue())
                                .eventData(eventData)
                                .build());

                        sink.complete();
                    } catch (Throwable e) {
                        emitRejectedOrError(sink, sessionId, userId, rawPageContextJson,
                                AiWorkflowType.LEARNING_PLAN.name(), e);
                    } finally {
                        UserContext.clear();
                    }
                })
        );

        return Flux.concat(Flux.just(paramEvent), workflowEvents);
    }

    //学习进度 Workflow 创建入口：planId 用入口查到的 ACTIVE 计划，request 只用原始 message（不信任 intent 结构化字段）
    private Flux<AiChatEventVO> streamLearningProgressWorkflowFromIntent(
            String message,
            LearningPlans targetPlan,
            List<AiWorkflowLearningProgressDTO.Candidate> candidates,
            PageContextDTO pageContext,
            String rawPageContextJson,
            Long userId,
            Long sessionId,
            String requestId
    ) {
        AiMessages userMessage = new AiMessages();
        userMessage.setSessionId(sessionId);
        userMessage.setRole(ROLE_USER);
        userMessage.setContent(message);
        userMessage.setPageContext(rawPageContextJson);
        userMessage.setCreatedAt(LocalDateTime.now());
        save(userMessage);

        AiWorkflowLearningProgressDTO workflowDTO = new AiWorkflowLearningProgressDTO();
        workflowDTO.setConversationId(sessionId);
        workflowDTO.setPlanId(targetPlan == null ? null : targetPlan.getId());
        workflowDTO.setCandidates(candidates == null || candidates.isEmpty() ? null : candidates);
        workflowDTO.setRequest(message);

        AiChatEventVO paramEvent = AiChatEventVO.builder()
                .eventType(AiChatEventType.PARAM.getValue())
                .eventData(Map.of(
                        "session", AiSessionVO.from(getOwnedSession(sessionId, userId)),
                        "userMessage", AiMessageVO.from(userMessage)
                ))
                .build();

        Flux<AiChatEventVO> workflowEvents = Flux.create(sink ->
                Schedulers.boundedElastic().schedule(() -> {
                    UserContext.set(userId);
                    try {
                        AiWorkflowStepEmitter emitter = new AiWorkflowStepEmitter() {
                            @Override
                            public void emit(String step, String status, String stepMessage) {
                                // 初次创建时 step 事件可以先不推给前端
                            }

                            @Override
                            public void emitContent(String step, String field, String delta) {
                                // 计划 JSON 不流式推前端，确认面板从 workflow 数据渲染
                            }
                        };

                        // ServiceImpl 的 create 内部会绑定 session 的 activeWorkflowRunId
                        // agentAutoStarted=true：这是 Planner 自动拉起的入口，run context 落标记供每会话上限统计
                        AiWorkflowRunVO workflow = aiWorkflowRunService.createLearningProgressWorkflow(workflowDTO, emitter, true, requestId);

                        AiMessages assistantMessage = saveStreamAssistantMessage(
                                sessionId,
                                userId,
                                rawPageContextJson,
                                buildLearningProgressAssistantContent(workflow),
                                Long.valueOf(workflow.getId())
                        );

                        AiSessions updatedSession = getOwnedSession(sessionId, userId);

                        Map<String, Object> eventData = new HashMap<>();
                        eventData.put("session", AiSessionVO.from(updatedSession));
                        eventData.put("assistantMessage", AiMessageVO.from(assistantMessage));
                        eventData.put("references", List.of());
                        eventData.put("workflow", workflow);

                        sink.next(AiChatEventVO.builder()
                                .eventType(AiChatEventType.STOP.getValue())
                                .eventData(eventData)
                                .build());

                        sink.complete();
                    } catch (Throwable e) {
                        emitRejectedOrError(sink, sessionId, userId, rawPageContextJson,
                                AiWorkflowType.LEARNING_PROGRESS.name(), e);
                    } finally {
                        UserContext.clear();
                    }
                })
        );

        return Flux.concat(Flux.just(paramEvent), workflowEvents);
    }

    //学习难点攻坚 Workflow 创建入口：planId 用入口查到的 ACTIVE 计划，request 只用原始 message（不信任 intent 结构化字段）
    private Flux<AiChatEventVO> streamLearningAssistWorkflowFromIntent(
            String message,
            LearningPlans targetPlan,
            List<AiWorkflowLearningAssistDTO.Candidate> candidates,
            PageContextDTO pageContext,
            String rawPageContextJson,
            Long userId,
            Long sessionId,
            String requestId
    ) {
        AiMessages userMessage = new AiMessages();
        userMessage.setSessionId(sessionId);
        userMessage.setRole(ROLE_USER);
        userMessage.setContent(message);
        userMessage.setPageContext(rawPageContextJson);
        userMessage.setCreatedAt(LocalDateTime.now());
        save(userMessage);

        AiWorkflowLearningAssistDTO workflowDTO = new AiWorkflowLearningAssistDTO();
        workflowDTO.setConversationId(sessionId);
        workflowDTO.setPlanId(targetPlan == null ? null : targetPlan.getId());
        workflowDTO.setCandidates(candidates == null || candidates.isEmpty() ? null : candidates);
        workflowDTO.setRequest(message);

        AiChatEventVO paramEvent = AiChatEventVO.builder()
                .eventType(AiChatEventType.PARAM.getValue())
                .eventData(Map.of(
                        "session", AiSessionVO.from(getOwnedSession(sessionId, userId)),
                        "userMessage", AiMessageVO.from(userMessage)
                ))
                .build();

        Flux<AiChatEventVO> workflowEvents = Flux.create(sink ->
                Schedulers.boundedElastic().schedule(() -> {
                    UserContext.set(userId);
                    try {
                        AiWorkflowStepEmitter emitter = new AiWorkflowStepEmitter() {
                            @Override
                            public void emit(String step, String status, String stepMessage) {
                                // 初次创建时 step 事件可以先不推给前端
                            }

                            @Override
                            public void emitContent(String step, String field, String delta) {
                                // 拆解 JSON 不流式推前端，确认面板从 workflow 数据渲染
                            }
                        };

                        // ServiceImpl 的 create 内部会绑定 session 的 activeWorkflowRunId
                        // agentAutoStarted=true：这是 Planner 自动拉起的入口，run context 落标记供每会话上限统计
                        AiWorkflowRunVO workflow = aiWorkflowRunService.createLearningAssistWorkflow(workflowDTO, emitter, true, requestId);

                        AiMessages assistantMessage = saveStreamAssistantMessage(
                                sessionId,
                                userId,
                                rawPageContextJson,
                                buildLearningAssistAssistantContent(workflow),
                                Long.valueOf(workflow.getId())
                        );

                        AiSessions updatedSession = getOwnedSession(sessionId, userId);

                        Map<String, Object> eventData = new HashMap<>();
                        eventData.put("session", AiSessionVO.from(updatedSession));
                        eventData.put("assistantMessage", AiMessageVO.from(assistantMessage));
                        eventData.put("references", List.of());
                        eventData.put("workflow", workflow);

                        sink.next(AiChatEventVO.builder()
                                .eventType(AiChatEventType.STOP.getValue())
                                .eventData(eventData)
                                .build());

                        sink.complete();
                    } catch (Throwable e) {
                        emitRejectedOrError(sink, sessionId, userId, rawPageContextJson,
                                AiWorkflowType.LEARNING_ASSIST.name(), e);
                    } finally {
                        UserContext.clear();
                    }
                })
        );

        return Flux.concat(Flux.just(paramEvent), workflowEvents);
    }

    //学习进度 CTA：调整诉求不清时给"已加载计划 + 进度 + 怎么调整"引导；其他情况引导确认面板
    private String buildLearningProgressAssistantContent(AiWorkflowRunVO workflow) {
        Object contextValue = workflow == null ? null : workflow.getContext();
        if (!(contextValue instanceof Map<?, ?> context)) {
            return "已创建学习进度 Workflow，请在下方面板确认调整后的学习计划。";
        }
        Object confirmation = context.get("confirmation");
        if (confirmation instanceof Map<?, ?> c && "REQUIREMENT".equals(String.valueOf(c.get("type")))) {
            StringBuilder content = new StringBuilder();
            Object oldPlanObj = context.get("oldPlan");
            if (!(oldPlanObj instanceof Map<?, ?>)) {
                //选计划阶段（候选歧义，尚未加载计划）→ 直接显示候选列表问题
                Object question = c.get("question");
                if (question != null && !String.valueOf(question).isBlank()) {
                    return String.valueOf(question);
                }
                content.append("已加载你当前的学习计划。");
            } else if (oldPlanObj instanceof Map<?, ?> oldPlan) {
                Object title = oldPlan.get("title");
                if (title != null && !String.valueOf(title).isBlank()) {
                    content.append("已加载你当前的学习计划《").append(title).append("》");
                } else {
                    content.append("已加载你当前的学习计划");
                }
                int done = 0;
                int total = 0;
                if (oldPlan.get("stages") instanceof List<?> stages) {
                    for (Object s : stages) {
                        if (!(s instanceof Map<?, ?> stage) || !(stage.get("tasks") instanceof List<?> tasks)) {
                            continue;
                        }
                        for (Object t : tasks) {
                            if (t instanceof Map<?, ?> task) {
                                total++;
                                if (Boolean.TRUE.equals(task.get("done"))) {
                                    done++;
                                }
                            }
                        }
                    }
                }
                content.append("（已完成 ").append(done).append("/").append(total).append(" 项）。");
            } else {
                content.append("已加载你当前的学习计划。");
            }
            content.append("告诉我具体想怎么调整——比如：加个新阶段、压缩整体周期、替换某些任务，")
                    .append("我会基于当前进度重新排一版计划。");
            return content.toString();
        }
        return "已创建学习进度 Workflow，请在下方面板确认调整后的学习计划。";
    }

    //攻坚助手消息：选计划/选阶段 → 显示候选列表问题；生成完成 → 讲解 + 任务点数引导确认面板
    private String buildLearningAssistAssistantContent(AiWorkflowRunVO workflow) {
        Object contextValue = workflow == null ? null : workflow.getContext();
        if (!(contextValue instanceof Map<?, ?> context)) {
            return "已创建难点攻坚 Workflow，请在下方面板确认拆解出的任务点。";
        }
        Object confirmation = context.get("confirmation");
        if (confirmation instanceof Map<?, ?> c && "REQUIREMENT".equals(String.valueOf(c.get("type")))) {
            //选计划/选阶段候选列表问题直接展示（说明文字在卡片 question 里）
            Object question = c.get("question");
            if (question != null && !String.valueOf(question).isBlank()) {
                return String.valueOf(question);
            }
            return "你想攻克哪个阶段？请回复序号或阶段名。";
        }
        //生成完成：讲解随消息留存 + 引导确认面板
        Object stepResultsObj = context.get("stepResults");
        if (stepResultsObj instanceof Map<?, ?> stepResults && stepResults.get("plan") instanceof Map<?, ?> plan) {
            StringBuilder content = new StringBuilder();
            Object explanation = plan.get("explanation");
            if (explanation != null && !String.valueOf(explanation).isBlank()) {
                content.append(explanation).append("\n\n");
            }
            int taskCount = 0;
            if (plan.get("stages") instanceof List<?> stages) {
                for (Object s : stages) {
                    if (s instanceof Map<?, ?> stage && stage.get("tasks") instanceof List<?> tasks) {
                        taskCount += tasks.size();
                    }
                }
            }
            content.append("已拆解为 ").append(taskCount).append(" 个任务点，请在下方面板确认。");
            return content.toString();
        }
        return "已创建难点攻坚 Workflow，请在下方面板确认拆解出的任务点。";
    }

    //弱模式 CTA：先给入门建议模板 + 站内引用，结尾抛钩子；其他情况引导确认面板
    private String buildLearningPlanAssistantContent(AiWorkflowRunVO workflow) {
        Object contextValue = workflow == null ? null : workflow.getContext();
        if (!(contextValue instanceof Map<?, ?> context)) {
            return "已创建学习规划 Workflow，请在下方面板确认生成的学习计划。";
        }
        Object confirmation = context.get("confirmation");
        if (confirmation instanceof Map<?, ?> c && "REQUIREMENT".equals(String.valueOf(c.get("type")))) {
            StringBuilder content = new StringBuilder();
            content.append("想系统学一门技术，一般建议按“基础 → 进阶 → 实践”三步走：")
                    .append("先吃透核心概念和原理，再深入源码与底层机制，最后用项目练手巩固。");

            Object ragObj = context.get("ragContext");
            if (ragObj instanceof Map<?, ?> rag
                    && rag.get("references") instanceof List<?> refs
                    && !refs.isEmpty()) {
                content.append("\n\n站内还有 ").append(refs.size())
                        .append(" 篇相关文章，制定计划时我会把它们作为学习材料。");
            }

            content.append("\n\n如果你愿意，我可以帮你制定一份详细的学习计划——")
                    .append("顺便告诉我：你现在的基础怎么样？计划学多久？");
            return content.toString();
        }
        return "已创建学习规划 Workflow，请在下方面板确认生成的学习计划。";
    }

    private String buildWorkflowAssistantContent(AiWorkflowRunVO workflow) {
        Object contextValue = workflow == null ? null : workflow.getContext();
        if (!(contextValue instanceof Map<?, ?> context)) {
            return "已创建文章创作 Workflow，请先确认生成方案。";
        }

        @SuppressWarnings("unchecked")
        String outline = workflowContextSupport.getResultString(
                (Map<String, Object>) (Object) context, "outline");
        if (outline.isBlank()) {
            return "已创建文章创作 Workflow，请先确认生成方案。";
        }

        return "已创建文章创作 Workflow，请先确认下面的大纲。\n\n" + outline;
    }

    /** 会话有 active workflow 时，优先把用户输入交给 Workflow 处理 */
    private Flux<AiChatEventVO> streamContinueActiveWorkflow(
            String message,
            PageContextDTO pageContext,
            String rawPageContextJson,
            Long userId,
            AiSessions session
    ) {
        Long sessionId = session.getId();
        Long workflowRunId = session.getActiveWorkflowRunId();

        AiMessages userMessage = new AiMessages();
        userMessage.setSessionId(sessionId);
        userMessage.setRole(ROLE_USER);
        userMessage.setContent(message);
        userMessage.setPageContext(rawPageContextJson);
        userMessage.setCreatedAt(LocalDateTime.now());
        save(userMessage);

        // 查 workflow
        AiWorkflowRunVO workflow;
        try {
            workflow = aiWorkflowRunService.getWorkflowRun(workflowRunId);
        } catch (Exception e) {
            // Workflow 不存在或无权访问 → 清空绑定，回普通聊天
            clearSessionActiveWorkflow(session);
            return fallbackToNormalChatAfterWorkflowGone(
                    message, pageContext, rawPageContextJson, userId, sessionId, userMessage);
        }

        if (workflow == null) {
            clearSessionActiveWorkflow(session);
            return fallbackToNormalChatAfterWorkflowGone(
                    message, pageContext, rawPageContextJson, userId, sessionId, userMessage);
        }

        AiWorkflowStatus status;
        try {
            status = AiWorkflowStatus.valueOf(workflow.getStatus());
        } catch (Exception e) {
            clearSessionActiveWorkflow(session);
            return fallbackToNormalChatAfterWorkflowGone(
                    message, pageContext, rawPageContextJson, userId, sessionId, userMessage);
        }

        // 已结束的状态 → 清空绑定，回普通聊天
        if (status == AiWorkflowStatus.COMPLETED
                || status == AiWorkflowStatus.CANCELLED
                || status == AiWorkflowStatus.FAILED) {
            clearSessionActiveWorkflow(session);
            return fallbackToNormalChatAfterWorkflowGone(
                    message, pageContext, rawPageContextJson, userId, sessionId, userMessage);
        }

        // WAITING_REQUIREMENT_CONFIRM：用户输入当作补充需求，走 reject 流程
        if (status == AiWorkflowStatus.WAITING_REQUIREMENT_CONFIRM) {
            try {
                workflow = aiWorkflowRunService.reject(workflowRunId, message);
            } catch (Exception e) {
                log.warn("Workflow 补充需求失败: workflowRunId={}, error={}", workflowRunId, e.getMessage());
                // 失败不阻塞，返回当前 workflow 状态让用户看到错误
            }
            return buildWorkflowStopFlux(sessionId, userId, rawPageContextJson, userMessage, workflow);
        }

        // WAITING_PLAN_CONFIRM / WAITING_OUTLINE_CONFIRM / WAITING_DRAFT_CONFIRM / WAITING_FILL_CONFIRM
        // 这些状态下的确认操作应走前端 approve/reject 按钮，普通输入提示用户
        if (status == AiWorkflowStatus.WAITING_PLAN_CONFIRM
                || status == AiWorkflowStatus.WAITING_OUTLINE_CONFIRM
                || status == AiWorkflowStatus.WAITING_DRAFT_CONFIRM
                || status == AiWorkflowStatus.WAITING_FILL_CONFIRM) {
            AiMessages assistantMessage = saveStreamAssistantMessage(
                    sessionId, userId, rawPageContextJson,
                    "当前 Workflow 正在等待确认，请使用底部的按钮操作。"
                            + "\n- 同意：继续下一步"
                            + "\n- 不同意：输入修改意见后发送",
                    workflowRunId
            );
            return buildSimpleStopFlux(sessionId, userId, userMessage, assistantMessage, workflow);
        }

        // RUNNING / PAUSED 等：静默返回当前 workflow 状态
        return buildWorkflowStopFlux(sessionId, userId, rawPageContextJson, userMessage, workflow);
    }

    /** Workflow 已结束或不存在时，清空绑定并回退到普通聊天 */
    private Flux<AiChatEventVO> fallbackToNormalChatAfterWorkflowGone(
            String message,
            PageContextDTO pageContext,
            String rawPageContextJson,
            Long userId,
            Long sessionId,
            AiMessages userMessage
    ) {
        AiSessions session = getOwnedSession(sessionId, userId);

        /*
         * 回退路径也必须使用新的 requestId。
         * 这样分类、Planner 和后续模型调用仍然可以串成一条链。
         */
        String requestId = UUID.randomUUID().toString();

        AiIntent intent = aiIntentClassifier.classify(
                message,
                pageContext
        );

        AgentDecision routeDecision = agentPlannerSupport.decide(
                message,
                intent,
                pageContext,
                userId,
                session
        );

        recordAgentDecisionTrace(
                requestId,
                userId,
                sessionId,
                message,
                intent,
                routeDecision
        );

        /*
         * CTA 使用已经保存好的 userMessage。
         * 不能调用 streamCtaMessage，
         * 否则会重复保存一条 userMessage。
         */
        if (routeDecision.getAction() == AgentAction.CTA) {
            return buildCtaForExistingUserMessage(
                    sessionId,
                    userId,
                    rawPageContextJson,
                    userMessage,
                    routeDecision
            );
        }

        /*
         * 重新启动 Workflow 前，
         * 必须删除回退路径已经保存的 userMessage。
         *
         * 各 Workflow 入口内部会重新保存 userMessage，
         * 所以这里不能留下重复消息。
         */
        if (routeDecision.getAction() == AgentAction.WORKFLOW) {

            // 文章创作
            if (routeDecision.isWorkflow(AiWorkflowType.CREATE_ARTICLE)) {
                removeById(userMessage.getId());

                return streamCreateArticleWorkflowFromIntent(
                        message,
                        intent,
                        pageContext,
                        rawPageContextJson,
                        userId,
                        sessionId
                );
            }

            // 文章优化
            if (routeDecision.isWorkflow(AiWorkflowType.OPTIMIZE_ARTICLE)) {
                removeById(userMessage.getId());

                return streamArticleOptimizeWorkflowFromIntent(
                        message,
                        intent,
                        pageContext,
                        rawPageContextJson,
                        userId,
                        sessionId
                );
            }

            // 学习难点攻坚
            if (routeDecision.isWorkflow(AiWorkflowType.LEARNING_ASSIST)) {
                Optional<Flux<AiChatEventVO>> routed =
                        routeLearningAssistWorkflow(
                                message,
                                intent == null
                                        ? null
                                        : intent.getLearningPlanRef(),
                                pageContext,
                                rawPageContextJson,
                                userId,
                                sessionId,
                                requestId
                        );

                if (routed.isPresent()) {
                    removeById(userMessage.getId());
                    return routed.get();
                }

                /*
                 * 没有可用 ACTIVE 学习计划时，
                 * 不创建新的学习计划，
                 * 直接返回 CTA。
                 */
                return buildCtaForExistingUserMessage(
                        sessionId,
                        userId,
                        rawPageContextJson,
                        userMessage,
                        routeDecision
                );
            }

            // 学习进度调整
            if (routeDecision.isWorkflow(AiWorkflowType.LEARNING_PROGRESS)) {
                Optional<Flux<AiChatEventVO>> routed =
                        routeLearningProgressWorkflow(
                                message,
                                intent == null
                                        ? null
                                        : intent.getLearningPlanRef(),
                                pageContext,
                                rawPageContextJson,
                                userId,
                                sessionId,
                                requestId
                        );

                if (routed.isPresent()) {
                    removeById(userMessage.getId());
                    return routed.get();
                }

                /*
                 * 没有可调整计划时，
                 * 与主入口保持一致，改为创建学习规划 Workflow。
                 */
                removeById(userMessage.getId());

                return streamLearningPlanWorkflowFromIntent(
                        message,
                        intent,
                        pageContext,
                        rawPageContextJson,
                        userId,
                        sessionId,
                        requestId
                );
            }

            // 学习规划
            if (routeDecision.isWorkflow(AiWorkflowType.LEARNING_PLAN)) {
                removeById(userMessage.getId());

                return streamLearningPlanWorkflowFromIntent(
                        message,
                        intent,
                        pageContext,
                        rawPageContextJson,
                        userId,
                        sessionId,
                        requestId
                );
            }
        }

        // CHAT / TOOL 继续走普通 SSE。下面沿用原来的普通聊天逻辑。
        // 否则走普通 SSE 聊天（复用流式逻辑，但 userMessage 已经保存了）
        AiPrompt prompt = aiPromptService.buildPrompt(message, pageContext, sessionId);
        prompt.setArticleToolsEnabled(shouldEnableArticleTools(routeDecision));
        prompt.setSessionId(sessionId);
        prompt.setLearningDashboardToolEnabled(
                routeDecision.isTool("getLearningDashboard")
        );

        List<ArticleRagContext> ragContexts;

        if (routeDecision.usesRetrieval("CURRENT_ARTICLE")) {
            ArticleRagContext currentArticleReference =
                    buildCurrentArticleReference(
                            routeDecision,
                            intent,
                            pageContext
                    );

            ragContexts = currentArticleReference == null
                    ? List.of()
                    : List.of(currentArticleReference);

        } else if (routeDecision.usesRetrieval("ARTICLE_SEARCH")) {
            ArticleRagSearchResult ragSearchResult =
                    articleRagSearchService.search(
                            message,
                            intent
                    );

            ragContexts = ragSearchResult.contexts();

            appendRagContextToPrompt(prompt, ragContexts);

        } else {
            ragContexts = List.of();
        }

        // 当前文章问答：把文章正文拼进 prompt 上下文
        String extraPromptContext =
                buildExtraPromptContextFromIntent(intent, pageContext);

        if ((extraPromptContext == null
                || extraPromptContext.isBlank())
                && routeDecision.usesRetrieval("CURRENT_ARTICLE")) {
            extraPromptContext =
                    buildArticleDetailContextFromIntent(intent, pageContext);
        }

        appendExtraPromptContext(prompt, extraPromptContext);

        StringBuilder fullReply = new StringBuilder();

        AiSessions updatedSession = getOwnedSession(sessionId, userId);

        AiChatEventVO paramEvent = AiChatEventVO.builder()
                .eventType(AiChatEventType.PARAM.getValue())
                .eventData(Map.of(
                        "session", AiSessionVO.from(updatedSession),
                        "userMessage", AiMessageVO.from(userMessage)
                ))
                .build();

        TokenUsageAccumulator usage = new TokenUsageAccumulator();
        Flux<AiChatEventVO> dataEvents = aiModelService.streamChat(prompt, requestId, usage)
                .doOnNext(fullReply::append)
                .map(chunk -> AiChatEventVO.builder()
                        .eventType(AiChatEventType.DATA.getValue())
                        .eventData(chunk)
                        .build());

        Mono<AiChatEventVO> stopEvent = Mono.fromSupplier(() -> {
            AiMessages assistantMessage = saveStreamAssistantMessage(
                    sessionId, userId, rawPageContextJson, fullReply.toString());
            //token 用量落库（含工具调用多轮累计）
            if (usage.getTotalTokens() > 0) {
                assistantMessage.setTokenCount((long) usage.getTotalTokens());
                updateById(assistantMessage);
            }

            AiSessions updated = getOwnedSession(sessionId, userId);

            Map<String, Object> eventData = new HashMap<>();
            eventData.put("session", AiSessionVO.from(updated));
            eventData.put("assistantMessage", AiMessageVO.from(assistantMessage));
            eventData.put("references", toRagReferences(ragContexts));

            return AiChatEventVO.builder()
                    .eventType(AiChatEventType.STOP.getValue())
                    .eventData(eventData)
                    .build();
        });

        return Flux.concat(
                Flux.just(paramEvent),
                dataEvents,
                stopEvent
        ).doFinally(signalType -> aiToolActionRegistry.clear(requestId));
    }

    //Agent 决策追踪：只做观测，记录失败绝不能影响正常聊天和 Workflow
    private void recordAgentDecisionTrace(
            String requestId,
            Long userId,
            Long sessionId,
            String message,
            AiIntent intent,
            AgentDecision decision
    ) {
        try {
            agentDecisionTraceSink.record(
                    AgentDecisionTrace.from(
                            requestId,
                            userId,
                            sessionId,
                            message,
                            intent,
                            decision
                    )
            );
        } catch (Exception e) {
            log.warn("Agent 决策记录失败: requestId={}, sessionId={}, error={}",
                    requestId, sessionId, e.getMessage());
        }
    }

    /**
     * 回退路径专用 CTA。
     *
     * 该路径的 userMessage 已经提前保存，
     * 所以这里只保存 assistantMessage，
     * 避免重复插入 userMessage。
     */
    private Flux<AiChatEventVO> buildCtaForExistingUserMessage(
            Long sessionId,
            Long userId,
            String rawPageContextJson,
            AiMessages userMessage,
            AgentDecision decision
    ) {
        String reason = decision == null
                || decision.getReason() == null
                || decision.getReason().isBlank()
                ? "当前需求还不够明确。"
                : decision.getReason();

        String content = buildPlannerCtaContent(
                decision,
                reason
        );

        AiMessages assistantMessage = saveStreamAssistantMessage(
                sessionId,
                userId,
                rawPageContextJson,
                content
        );

        return buildSimpleStopFlux(
                sessionId,
                userId,
                userMessage,
                assistantMessage,
                null
        );
    }

    /** Learning Agent CTA：只保存普通对话消息，不创建 run，也不绑定 active workflow。 */
    private Flux<AiChatEventVO> streamCtaMessage(
            Long sessionId,
            Long userId,
            String rawPageContextJson,
            String userMessageText,
            AgentDecision decision
    ) {
        AiMessages userMessage = new AiMessages();
        userMessage.setSessionId(sessionId);
        userMessage.setRole(ROLE_USER);
        userMessage.setContent(userMessageText);
        userMessage.setPageContext(rawPageContextJson);
        userMessage.setCreatedAt(LocalDateTime.now());
        save(userMessage);

        String reason = decision == null || decision.getReason() == null || decision.getReason().isBlank()
                ? "当前学习需求还不够明确。"
                : decision.getReason();

        String content = buildPlannerCtaContent(decision, reason);

        AiMessages assistantMessage = saveStreamAssistantMessage(
                sessionId,
                userId,
                rawPageContextJson,
                content
        );

        return buildSimpleStopFlux(sessionId, userId, userMessage, assistantMessage, null);
    }

    /**
     * 根据最终决策类型生成 CTA 文案。
     *
     * CTA 只保存普通消息：
     * - 不创建 Workflow Run
     * - 不绑定 activeWorkflowRunId
     */
    private String buildPlannerCtaContent(
            AgentDecision decision,
            String reason
    ) {
        String intent = decision == null ? null : decision.getIntent();

        if ("OPTIMIZE_ARTICLE_WORKFLOW".equals(intent)) {
            return "我可以帮你优化文章，但当前还缺少明确的文章上下文。\n\n"
                    + "当前判断：" + reason + "\n"
                    + "请从文章详情页发起优化，或者明确告诉我需要优化哪篇文章。";
        }

        if ("CREATE_ARTICLE_WORKFLOW".equals(intent)) {
            return "我可以帮你创建文章，但还需要确认文章主题或具体要求。\n\n"
                    + "当前判断：" + reason + "\n"
                    + "请告诉我想写什么主题，例如：Redis 缓存、Kafka 消息队列、RAG 检索增强。";
        }

        if ("ARTICLE_DETAIL_QA".equals(intent)) {
            return "我可以回答当前文章的问题，但当前没有拿到有效的文章上下文。\n\n"
                    + "当前判断：" + reason + "\n"
                    + "请在文章详情页继续提问。";
        }

        return "我可以继续普通讲解，也可以为你启动对应的学习流程。\n\n"
                + "当前判断：" + reason + "\n"
                + "如果只是想聊天或解释概念，可以直接继续问；"
                + "如果要进入学习流程，请明确告诉我。";
    }

    /**
     * 游客触发 Workflow 或 Tool 时的限制文案。
     */
    private String buildGuestDecisionContent(
            String message,
            AiIntent intent,
            AgentDecision decision
    ) {
        String currentIntent =
                decision == null ? null : decision.getIntent();

        /*
         * 文章创作保留原来的主题判断。
         * 主题不明确时先追问，不急着提示登录。
         */
        if ("CREATE_ARTICLE_WORKFLOW".equals(currentIntent)) {
            if (createArticleWorkflowHandler.isRequirementUnclear(
                    message,
                    intent == null ? null : intent.getTopicEvidence()
            )) {
                return "可以帮你写文章。你想写什么主题？\n"
                        + "例如：Redis 缓存、Kafka 消息队列、RAG 检索增强。";
            }

            return "文章创作 Workflow 需要登录后使用，请先登录后再试。";
        }

        if ("OPTIMIZE_ARTICLE_WORKFLOW".equals(currentIntent)) {
            return "文章优化 Workflow 需要登录后使用，请先登录后再试。";
        }

        if ("LEARNING_PLAN_QUERY".equals(currentIntent)
                || (decision != null
                && decision.isTool("getLearningDashboard"))) {
            return "学习计划查询需要登录后使用，请先登录后再试。";
        }

        if ("LEARNING_PLAN".equals(currentIntent)
                || "LEARNING_PROGRESS".equals(currentIntent)
                || "LEARNING_ASSIST".equals(currentIntent)) {
            return "学习 Workflow 需要登录后使用，请先登录后再试。";
        }

        return "这个功能需要登录后使用，请先登录后再试。";
    }

    /**
     * 游客 CTA 文案。
     *
     * 游客没有持久化 session，
     * 所以这里只返回一次性提示，不保存消息。
     */
    private String buildGuestCtaContent(
            AgentDecision decision
    ) {
        String reason = decision == null
                ? "当前需求还不够明确。"
                : decision.getReason();

        if (reason == null || reason.isBlank()) {
            reason = "当前需求还不够明确。";
        }

        return "我还需要你补充一点信息，才能判断下一步怎么处理。\n\n"
                + "当前判断：" + reason + "\n"
                + "你可以继续描述具体问题，或者登录后使用完整的 Workflow 和学习计划能力。";
    }

    /** 构建带 workflow 的 STOP Flux（无流式内容） */
    private Flux<AiChatEventVO> buildWorkflowStopFlux(
            Long sessionId, Long userId, String rawPageContextJson,
            AiMessages userMessage, AiWorkflowRunVO workflow
    ) {
        AiMessages assistantMessage = saveStreamAssistantMessage(
                sessionId, userId, rawPageContextJson,
                buildWorkflowAssistantContent(workflow),
                workflow == null ? null : Long.valueOf(workflow.getId()));

        return buildSimpleStopFlux(sessionId, userId, userMessage, assistantMessage, workflow);
    }

    /** 构建简单 STOP Flux（带 assistantMessage 和可选的 workflow） */
    private Flux<AiChatEventVO> buildSimpleStopFlux(
            Long sessionId, Long userId,
            AiMessages userMessage, AiMessages assistantMessage,
            AiWorkflowRunVO workflow
    ) {
        AiSessions updatedSession = getOwnedSession(sessionId, userId);

        AiChatEventVO paramEvent = AiChatEventVO.builder()
                .eventType(AiChatEventType.PARAM.getValue())
                .eventData(Map.of(
                        "session", AiSessionVO.from(updatedSession),
                        "userMessage", AiMessageVO.from(userMessage)
                ))
                .build();

        Map<String, Object> eventData = new HashMap<>();
        eventData.put("session", AiSessionVO.from(updatedSession));
        eventData.put("assistantMessage", AiMessageVO.from(assistantMessage));
        eventData.put("references", List.of());
        if (workflow != null) {
            eventData.put("workflow", workflow);
        }

        AiChatEventVO stopEvent = AiChatEventVO.builder()
                .eventType(AiChatEventType.STOP.getValue())
                .eventData(eventData)
                .build();

        return Flux.just(paramEvent, stopEvent);
    }

    /** 清空 session 的 activeWorkflowRunId */
    private void clearSessionActiveWorkflow(AiSessions session) {
        if (session.getActiveWorkflowRunId() == null) {
            return;
        }
        session.setActiveWorkflowRunId(null);
    }

    //保存停止生成的内容
    private AiMessages saveStreamAssistantMessage(
            Long sessionId,
            Long userId,
            String rawPageContextJson,
            String content
    ) {
        return saveStreamAssistantMessage(sessionId, userId, rawPageContextJson, content, null);
    }
    private AiMessages saveStreamAssistantMessage(
            Long sessionId,
            Long userId,
            String rawPageContextJson,
            String content,
            Long workflowRunId
    ){
        AiMessages assistantMessage = new AiMessages();
        assistantMessage.setSessionId(sessionId);
        if (workflowRunId != null) {
            assistantMessage.setWorkflowRunId(String.valueOf(workflowRunId));
        }
        assistantMessage.setRole(ROLE_ASSISTANT);
        assistantMessage.setContent(content);
        assistantMessage.setPageContext(rawPageContextJson);
        assistantMessage.setCreatedAt(LocalDateTime.now());

        save(assistantMessage);

        aiSessionService.lambdaUpdate()
                .eq(AiSessions::getId,sessionId)
                .eq(AiSessions::getUserId,userId)
                .set(AiSessions::getUpdatedAt,LocalDateTime.now())
                .update();

        return assistantMessage;
    }

    //游客流式逻辑，不入库
    private Flux<AiChatEventVO> streamGuestChat(String message, PageContextDTO pageContext) {
        String now = LocalDateTime.now().toString();
        StringBuilder fullReply = new StringBuilder();

        AiSessionVO guestSession = new AiSessionVO();
        guestSession.setId("guest");
        guestSession.setTitle("游客临时会话");
        guestSession.setCreatedAt(LocalDateTime.now());
        guestSession.setUpdatedAt(LocalDateTime.now());

        AiMessageVO userMessage = new AiMessageVO();
        userMessage.setId("guest-user-" + System.currentTimeMillis());
        userMessage.setSessionId("guest");
        userMessage.setRole("user");
        userMessage.setContent(message);
        userMessage.setCreatedAt(LocalDateTime.now());

        // 游客也先经过统一 Planner。
        // 但游客不能真正执行 Workflow 或学习查询 Tool。
        AiIntent intent = aiIntentClassifier.classify(
                message,
                pageContext
        );

        String requestId = UUID.randomUUID().toString();

        AgentDecision guestDecision = agentPlannerSupport.decide(
                message,
                intent,
                pageContext,
                null,
                null
        );

        recordAgentDecisionTrace(
                requestId,
                null,
                null,
                message,
                intent,
                guestDecision
        );

        /*
         * 游客不能执行 Workflow。
         *
         * 文章创作、文章优化、学习规划、学习调整、难点攻坚，
         * 都统一返回登录提示。
         */
        if (guestDecision.getAction() == AgentAction.WORKFLOW
                || guestDecision.getAction() == AgentAction.TOOL) {

            AiMessageVO assistantMessage = new AiMessageVO();
            assistantMessage.setId(
                    "guest-ai-" + System.currentTimeMillis()
            );
            assistantMessage.setSessionId("guest");
            assistantMessage.setRole("assistant");

            assistantMessage.setContent(
                    buildGuestDecisionContent(
                            message,
                            intent,
                            guestDecision
                    )
            );

            assistantMessage.setCreatedAt(LocalDateTime.now());

            Map<String, Object> eventData = new HashMap<>();
            eventData.put("session", guestSession);
            eventData.put("assistantMessage", assistantMessage);
            eventData.put("references", List.of());

            return Flux.just(
                    AiChatEventVO.builder()
                            .eventType(AiChatEventType.PARAM.getValue())
                            .eventData(Map.of(
                                    "session", guestSession,
                                    "userMessage", userMessage
                            ))
                            .build(),

                    AiChatEventVO.builder()
                            .eventType(AiChatEventType.STOP.getValue())
                            .eventData(eventData)
                            .build()
            );
        }

        /*
         * CTA 也不创建 Workflow。
         * 游客只返回澄清文案。
         */
        if (guestDecision.getAction() == AgentAction.CTA) {
            AiMessageVO assistantMessage = new AiMessageVO();
            assistantMessage.setId(
                    "guest-ai-" + System.currentTimeMillis()
            );
            assistantMessage.setSessionId("guest");
            assistantMessage.setRole("assistant");
            assistantMessage.setContent(
                    buildGuestCtaContent(guestDecision)
            );
            assistantMessage.setCreatedAt(LocalDateTime.now());

            Map<String, Object> eventData = new HashMap<>();
            eventData.put("session", guestSession);
            eventData.put("assistantMessage", assistantMessage);
            eventData.put("references", List.of());

            return Flux.just(
                    AiChatEventVO.builder()
                            .eventType(AiChatEventType.PARAM.getValue())
                            .eventData(Map.of(
                                    "session", guestSession,
                                    "userMessage", userMessage
                            ))
                            .build(),

                    AiChatEventVO.builder()
                            .eventType(AiChatEventType.STOP.getValue())
                            .eventData(eventData)
                            .build()
            );
        }

        AiArticleActionCommand articleActionFromIntent = buildArticleActionFromDecision(guestDecision, intent, pageContext);
        String extraPromptContext = buildExtraPromptContextFromIntent(intent, pageContext);
        if (extraPromptContext == null || extraPromptContext.isBlank()) {
            extraPromptContext = buildArticleDetailContextFromIntent(intent, pageContext);
        }

        AiNavigateCommand navigateFromIntent = buildNavigateFromDecision(guestDecision, intent, pageContext);
        AiEditorCommand editorActionFromIntent = buildEditorActionFromDecision(guestDecision, intent);

        // 拼 prompt（游客无 sessionId，跳过历史记忆）
        AiPrompt prompt = aiPromptService.buildPrompt(message, pageContext, null);
        prompt.setArticleToolsEnabled(shouldEnableArticleTools(guestDecision));
        appendExtraPromptContext(prompt, extraPromptContext);

        String actionResultPromptContext = buildActionResultPromptContext(navigateFromIntent);
        appendExtraPromptContext(prompt,actionResultPromptContext);

        boolean currentArticleRetrieval = guestDecision.usesRetrieval("CURRENT_ARTICLE");
        boolean articleSearchRetrieval = guestDecision.usesRetrieval("ARTICLE_SEARCH");

        ArticleRagContext currentArticleReference = buildCurrentArticleReference(guestDecision, intent, pageContext);

        List<ArticleRagContext> ragContexts;

        if (currentArticleRetrieval) {
            ragContexts = currentArticleReference == null ? List.of() : List.of(currentArticleReference);
        } else if (articleSearchRetrieval) {
            ArticleRagSearchResult ragSearchResult = articleRagSearchService.search(message, intent);
            ragContexts = ragSearchResult.contexts();

            appendRagContextToPrompt(prompt, ragContexts);
        } else {
            ragContexts = List.of();
        }


        AiChatEventVO paramEvent = AiChatEventVO.builder()
                .eventType(AiChatEventType.PARAM.getValue())
                .eventData(Map.of(
                        "session", guestSession,
                        "userMessage", userMessage
                ))
                .build();

        Flux<AiChatEventVO> dataEvents = aiModelService.streamChat(prompt,requestId,new TokenUsageAccumulator())
                .doOnNext(fullReply::append)
                .doFinally(signalType -> log.info(
                        "游客 dataEvents 结束，signal={}, replyLen={}",
                        signalType, fullReply.length()
                ))
                .map(chunk -> AiChatEventVO.builder()
                        .eventType(AiChatEventType.DATA.getValue())
                        .eventData(chunk)
                        .build());

        Mono<AiChatEventVO> stopEvent = Mono.fromSupplier(() -> {
            String reply = fullReply.toString();

            AiMessageVO assistantMessage = new AiMessageVO();
            assistantMessage.setId("guest-ai-" + System.currentTimeMillis());
            assistantMessage.setSessionId("guest");
            assistantMessage.setRole("assistant");
            assistantMessage.setContent(reply);
            assistantMessage.setCreatedAt(LocalDateTime.now());

            Map<String, Object> eventData = new HashMap<>();
            eventData.put("session", guestSession);
            eventData.put("assistantMessage", assistantMessage);
            eventData.put("references", toRagReferences(ragContexts));


            // 意图分类器的 navigateFromIntent 可能缺少 param，降级走 Tool Calling
            AiNavigateCommand navigate = navigateFromIntent;
            if (isNavigateMissingRequiredParam(navigate)) {
                navigate = null;
            }
            if (navigate == null) {
                navigate = getNavigateFromToolRegistry(requestId, guestDecision, intent);
            }
            if (navigate != null) {
                eventData.put("navigate", navigate);
            }

            AiEditorCommand editorAction = editorActionFromIntent;
            if (editorAction == null) {
                editorAction = getEditorActionFromToolRegistry(requestId, guestDecision);
            }
            // fillArticle 只能从 Workflow 的 editorAction 出来，普通聊天不允许直接填充文章
            if (editorAction != null && "fillArticle".equals(editorAction.getType())) {
                editorAction = null;
            }
            if(editorAction != null){
                eventData.put("editorAction", editorAction);
            }

            AiArticleActionCommand articleAction = articleActionFromIntent;
            // 是否允许读取 Tool Registry 由 getArticleActionFromToolRegistry 自己判断
            if (articleAction == null) {
                articleAction = getArticleActionFromToolRegistry(
                        requestId,
                        guestDecision
                );
            }
            if (articleAction != null) {
                eventData.put("articleAction", articleAction);
            }

            return AiChatEventVO.builder()
                    .eventType(AiChatEventType.STOP.getValue())
                    .eventData(eventData)
                    .build();
        }).doOnError(e -> log.error("游客 stopEvent 构建失败", e));

        return Flux.concat(
                Flux.just(paramEvent),
                dataEvents,
                stopEvent
        ).doFinally(signalType -> {
            log.info("游客 SSE 流结束，signal={}, replyLen={}", signalType, fullReply.length());
            aiToolActionRegistry.clear(requestId);
        });
    }

    /** 需要 param 的 target（article、userProfile），param 为空时说明意图分类器无法确定具体 ID */
    private boolean isNavigateMissingRequiredParam(AiNavigateCommand navigate) {
        if (navigate == null) {
            return false;
        }
        String target = navigate.getTarget();
        if (!"article".equals(target) && !"userProfile".equals(target)) {
            return false;
        }
        return navigate.getParam() == null || navigate.getParam().isBlank();
    }

    /**
     * 从 Tool Registry 读取导航动作，受 Planner 决策约束。
     *
     * 普通聊天时即使模型误调用导航 Tool，decision.intent 不匹配也不会执行。
     */
    private AiNavigateCommand getNavigateFromToolRegistry(
            String requestId,
            AgentDecision decision,
            AiIntent intent
    ) {
        if (decision == null
                || !"NAVIGATE".equals(decision.getIntent())
                || !"navigate".equals(decision.getClientActionType())) {
            return null;
        }

        AiNavigateCommand command =
                aiToolActionRegistry.getNavigate(requestId);

        if (command == null) {
            return null;
        }

        if (intent != null
                && intent.getTarget() != null
                && !intent.getTarget().equals(command.getTarget())) {
            log.warn("工具导航目标与 Planner 决策不一致，拒绝执行");
            return null;
        }

        return command;
    }

    private AiEditorCommand getEditorActionFromToolRegistry(
            String requestId,
            AgentDecision decision
    ) {
        if (decision == null
                || !"EDITOR_ACTION".equals(decision.getIntent())
                || decision.getClientActionType() == null) {
            return null;
        }

        AiEditorCommand command =
                aiToolActionRegistry.getEditor(requestId);

        if (command == null
                || !decision.getClientActionType().equals(command.getType())) {
            log.warn("工具编辑器动作与 Planner 决策不一致，拒绝执行");
            return null;
        }

        return command;
    }

    private AiArticleActionCommand getArticleActionFromToolRegistry(
            String requestId,
            AgentDecision decision
    ) {
        if (decision == null
                || !"ARTICLE_ACTION".equals(decision.getIntent())
                || decision.getClientActionType() == null) {
            return null;
        }

        AiArticleActionCommand command =
                aiToolActionRegistry.getArticleAction(requestId);

        if (command == null
                || !decision.getClientActionType().equals(command.getType())) {
            log.warn("工具文章动作与 Planner 决策不一致，拒绝执行");
            return null;
        }

        return command;
    }


    //第一个：根据意图生成文章动作。
    private AiArticleActionCommand buildArticleActionFromDecision(
            AgentDecision decision,
            AiIntent intent,
            PageContextDTO pageContext
    ) {
        if (decision == null
                || !"ARTICLE_ACTION".equals(decision.getIntent())
                || decision.getClientActionType() == null) {
            return null;
        }

        String articleId = firstNonBlank(
                pageContext == null ? null : pageContext.getArticleId(),
                intent == null ? null : intent.getArticleId()
        );

        if (articleId == null || articleId.isBlank()) {
            return null;
        }

        AiArticleActionCommand command = new AiArticleActionCommand();
        command.setType(decision.getClientActionType());
        command.setArticleId(articleId);

        if (intent != null) {
            command.setContent(intent.getContent());
        }

        return command;
    }
    //第二个：根据意图准备额外上下文
    private String buildExtraPromptContextFromIntent(AiIntent intent,PageContextDTO pageContext){
        if (intent == null || !"USER_PROFILE_INSIGHT".equals(intent.getIntent())) {
            return null;
        }
        String userId = intent.getUserId();

        if((userId == null || userId.isBlank())
                && pageContext != null
                && pageContext.getUserId() != null
                && !pageContext.getUserId().isBlank())
        {
            userId = pageContext.getUserId();
        }

        if (userId == null || userId.isBlank()) {
            return "用户想了解当前主页信息，但缺少 userId，无法查询用户画像。";
        }

        return aiUserProfileTools.getUserProfileInsight(Long.valueOf(userId));
    }
    //第三个：拼进 prompt
    private void appendExtraPromptContext(AiPrompt prompt, String extraPromptContext) {
        if (prompt == null || extraPromptContext == null || extraPromptContext.isBlank()) {
            return;
        }

        prompt.setFinalPromptContext(
                prompt.getFinalPromptContext()
                        + "\n\n## 意图识别补充上下文\n"
                        + extraPromptContext
        );
    }

    //关于路由跳转的结构
    private AiNavigateCommand buildNavigateFromDecision(
            AgentDecision decision,
            AiIntent intent,
            PageContextDTO pageContext
    ) {
        if (decision == null
                || !"NAVIGATE".equals(decision.getIntent())
                || !"navigate".equals(decision.getClientActionType())
                || intent == null) {
            return null;
        }

        String target = intent.getTarget();
        String param = intent.getParam();

        if ("article".equals(target)
                && (param == null || param.isBlank())
                && pageContext != null) {
            param = pageContext.getArticleId();
        }

        if ("userProfile".equals(target)
                && (param == null || param.isBlank())
                && pageContext != null) {
            param = firstNonBlank(
                    pageContext.getAuthorId(),
                    resolveAuthorIdByArticleId(intent, pageContext)
            );
        }

        if (target == null || target.isBlank()) {
            return null;
        }

        return new AiNavigateCommand(target, param);
    }
    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private String resolveAuthorIdByArticleId(AiIntent intent,PageContextDTO pageContext){
        String articleId = null;

        if (pageContext != null && pageContext.getArticleId() != null) {
            articleId = pageContext.getArticleId();
        }

        if ((articleId == null || articleId.isBlank()) && intent.getArticleId() != null) {
            articleId = intent.getArticleId();
        }

        if (articleId == null || articleId.isBlank()) {
            return null;
        }

        Articles article = articlesService.getById(Long.valueOf(articleId));

        if (article == null || article.getAuthorId() == null) {
            return null;
        }

        return article.getAuthorId().toString();
    }

    //关于文章的保存和发布的结构
    private AiEditorCommand buildEditorActionFromDecision(
            AgentDecision decision,
            AiIntent intent
    ) {
        if (decision == null
                || !"EDITOR_ACTION".equals(decision.getIntent())
                || decision.getClientActionType() == null) {
            return null;
        }

        String actionType = decision.getClientActionType();

        if (!"saveDraft".equals(actionType)
                && !"publish".equals(actionType)) {
            return null;
        }

        AiEditorCommand command = new AiEditorCommand();
        command.setType(actionType);
        return command;
    }

    //关于文章详情页的文章总结
    private String buildArticleDetailContextFromIntent(AiIntent intent,PageContextDTO pageContext){
        if(intent == null || !"ARTICLE_DETAIL_QA".equals(intent.getIntent())){
            return null;
        }

        String articleId = resolveArticleIdFromIntent(intent,pageContext);

        if (articleId == null || articleId.isBlank()) {
            return "用户想询问当前文章内容，但缺少 articleId，无法读取文章详情。";
        }

        Long id;
        try{
            id = Long.valueOf(articleId);
        }
        catch (Exception e){
            return "当前文章ID格式错误，无法读取文章详情。";
        }

        Articles article = articlesService.lambdaQuery()
                .select(
                        Articles::getId,
                        Articles::getTitle,
                        Articles::getSummary,
                        Articles::getContent
                )
                .eq(Articles::getId,id)
                .eq(Articles::getStatus, BlogConstants.ArticlesStatus.PUBLISHED)
                .one();

        if (article == null) {
            return "当前文章不存在或未发布。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("当前文章内容：\n");
        sb.append("标题：").append(article.getTitle()).append("\n");

        if (article.getSummary() != null && !article.getSummary().isBlank()) {
            sb.append("摘要：").append(article.getSummary()).append("\n");
        }

        sb.append("正文：\n")
                .append(limitText(article.getContent(), 9000))
                .append("\n");

        sb.append("\n请基于以上文章内容回答用户问题，不要编造文章中没有的信息。");
        sb.append("回答中的关键结论后面请使用来源编号 [1]，因为当前文章会作为参考来源 [1] 展示。");

        return sb.toString();
    }
    private String limitText(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "\n\n[文章内容过长，后半部分已省略]";
    }

    private String resolveArticleIdFromIntent(AiIntent intent,PageContextDTO pageContext){
        String articleId = intent == null ? null : intent.getArticleId();

        if((articleId == null || articleId.isBlank())
        && pageContext != null
        && !pageContext.getArticleId().isBlank()
        && pageContext.getArticleId() != null){
            articleId = pageContext.getArticleId();
        }

        return articleId;
    }

    /**
     * 根据 Planner 的检索模式加载当前文章引用。
     *
     * retrievalMode 决定是否读取当前文章；
     * intent/pageContext 只提供 articleId 候选。
     */
    private ArticleRagContext buildCurrentArticleReference(
            AgentDecision decision,
            AiIntent intent,
            PageContextDTO pageContext
    ) {
        if (decision == null
                || !decision.usesRetrieval("CURRENT_ARTICLE")) {
            return null;
        }

        String articleId =
                resolveArticleIdFromIntent(intent, pageContext);

        if (articleId == null || articleId.isBlank()) {
            return null;
        }

        Long id;

        try {
            id = Long.valueOf(articleId);
        } catch (Exception e) {
            return null;
        }

        Articles article = articlesService.lambdaQuery()
                .select(
                        Articles::getId,
                        Articles::getTitle,
                        Articles::getSummary,
                        Articles::getContent
                )
                .eq(Articles::getId, id)
                .eq(
                        Articles::getStatus,
                        BlogConstants.ArticlesStatus.PUBLISHED
                )
                .one();

        if (article == null) {
            return null;
        }

        StringBuilder snippet = new StringBuilder();

        snippet.append("当前文章内容：\n");
        snippet.append("标题：")
                .append(article.getTitle())
                .append("\n");

        if (article.getSummary() != null
                && !article.getSummary().isBlank()) {
            snippet.append("摘要：")
                    .append(article.getSummary())
                    .append("\n");
        }

        snippet.append("正文：\n")
                .append(limitText(article.getContent(), 9000))
                .append("\n");

        snippet.append(
                "\n请基于以上文章内容回答用户问题，"
                        + "不要编造文章中没有的信息。"
        );

        return new ArticleRagContext(
                article.getId(),
                article.getTitle(),
                0,
                snippet.toString()
        );
    }


    private String buildActionResultPromptContext(AiNavigateCommand navigate) {
        if (navigate == null) {
            return null;
        }

        if ("userProfile".equals(navigate.getTarget())) {
            if (navigate.getParam() != null && !navigate.getParam().isBlank()) {
                return """
                    系统动作结果：
                    后端已经根据当前文章ID查询到作者ID。
                    本轮回答结束后，前端会自动跳转到该作者主页。
                    你只需要简短回复用户，例如：好的，正在为你打开作者主页。
                    不要说无法获取作者ID。
                    """;
            }

            return """
                系统动作结果：
                后端没有解析到作者ID，因此无法跳转到作者主页。
                请如实告诉用户当前无法跳转。
                """;
        }

        return null;
    }

    private void appendRagContextToPrompt(AiPrompt prompt,List<ArticleRagContext> contexts){
        if (prompt == null || contexts == null || contexts.isEmpty()) {
            return;
        }

        prompt.setFinalPromptContext(
                articleRagPromptBuilder.buildPrompt(prompt.getFinalPromptContext(),contexts)
        );
    }
    private List<ArticleRagReferenceVO> toRagReferences(List<ArticleRagContext> contexts){
        if (contexts == null || contexts.isEmpty()) {
            return List.of();
        }

        //同时列表去重，不能两个chunk同属一篇文章，而前端却显示两次来自同一个文章
        Map<Long,ArticleRagReferenceVO> referenceMap = new LinkedHashMap<>();

        for(ArticleRagContext context : contexts){
            if(context.articleId() == null){
                continue;
            }

            referenceMap.putIfAbsent(
                    context.articleId(),
                    ArticleRagReferenceVO.from(context)
            );
        }

        return List.copyOf(referenceMap.values());
    }

    @Override
    @Transactional
    public void deleteMessage(Long sessionId, Long messageId) {
        Long userId = UserContext.get();
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }

        AiSessions session = aiSessionMapper.selectById(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在");
        }
        if (!session.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权操作该会话");
        }

        AiMessages message = getById(messageId);
        if (message == null) {
            throw new IllegalArgumentException("消息不存在");
        }
        if (!message.getSessionId().equals(sessionId)) {
            throw new IllegalArgumentException("消息不属于该会话");
        }

        // 实体里 workflowRunId 是 String（雪花 ID 前端精度约定），库里是 BIGINT
        Long workflowRunId = message.getWorkflowRunId() == null
                ? null
                : Long.valueOf(message.getWorkflowRunId());

        removeById(messageId);

        if (workflowRunId != null) {
            cleanupWorkflowIfNoMessageReferences(session, workflowRunId);
        }
    }

    /** 删除消息后联动清理：若 Workflow 已无消息引用且已结束，连带删除 step log 和 run */
    private void cleanupWorkflowIfNoMessageReferences(AiSessions session, Long workflowRunId) {
        Long refCount = lambdaQuery()
                .eq(AiMessages::getWorkflowRunId, workflowRunId)
                .count();

        if (refCount != null && refCount > 0) {
            return;
        }

        AiWorkflowRun run = aiWorkflowRunMapper.selectById(workflowRunId);
        if (run == null) {
            return;
        }

        // 严格模式：只允许删除已结束 Workflow 的消息，进行中必须走取消流程
        if (!isFinishedWorkflowStatus(run.getStatus())) {
            throw new IllegalArgumentException("Workflow 正在进行中，请先取消后再删除消息");
        }

        aiWorkflowStepLogMapper.delete(new LambdaQueryWrapper<AiWorkflowStepLog>()
                .eq(AiWorkflowStepLog::getWorkflowRunId, workflowRunId));

        aiWorkflowRunMapper.deleteById(workflowRunId);
    }

    private boolean isFinishedWorkflowStatus(String status) {
        return AiWorkflowStatus.COMPLETED.name().equals(status)
                || AiWorkflowStatus.CANCELLED.name().equals(status)
                || AiWorkflowStatus.FAILED.name().equals(status);
    }

    /**
     * 是否暴露文章相关 Tool。
     *
     * 文章是否需要检索由 Planner 的 retrievalMode 决定。
     */
    private boolean shouldEnableArticleTools(
            AgentDecision decision
    ) {
        if (decision == null) {
            return false;
        }

        return decision.usesRetrieval("ARTICLE_SEARCH")
                || decision.usesRetrieval("CURRENT_ARTICLE");
    }

}
