package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.errorprone.annotations.Var;
import com.hailin.blogsystem.ai.rag.ArticleRagPromptBuilder;
import com.hailin.blogsystem.ai.rag.ArticleRagRetrieveService;
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
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private final AiSessionService aiSessionService;
    private final ObjectMapper objectMapper;

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final Pattern NAV_ARTICLE_ID_PATTERN = Pattern.compile("(?:文章|详情).*?(\\d+)");
    private static final Pattern NAV_USER_ID_PATTERN = Pattern.compile("(?:用户|作者).*?(?:主页|空间|个人页).*?(\\d+)");

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

        if(session.getActiveWorkflowRunId() != null){
            return streamContinueActiveWorkflow(
                    message,
                    pageContext,
                    rawPageContextJson,
                    userId,
                    session
            );
        }

        // 先用 LLM 分类意图，再决定是否起 Workflow——不在入口猜自然语言（分类后还有确定性兜底）
        AiIntent intent = classifyWithFallback(message, pageContext);

        // 创建文章意图（CREATE_ARTICLE_WORKFLOW 或兼容的 EDITOR_ACTION+fillArticle）→ 起 Workflow
        if (isCreateArticleWorkflowIntent(intent)) {
            return streamCreateArticleWorkflowFromIntent(
                    message,
                    intent,
                    pageContext,
                    rawPageContextJson,
                    userId,
                    sessionId
            );
        }


        AiArticleActionCommand articleActionFromIntent = buildArticleActionFromIntent(intent, pageContext);
        String extraPromptContext = buildExtraPromptContextFromIntent(intent, pageContext);
        if (extraPromptContext == null || extraPromptContext.isBlank()) {
            extraPromptContext = buildArticleDetailContextFromIntent(intent, pageContext);
        }


        AiNavigateCommand navigateFromIntent = buildNavigateFromIntent(intent,pageContext);
        AiEditorCommand editorActionFromIntent = buildEditorActionFromIntent(intent,message);

        // 拼完整 prompt（含历史记忆 + 页面上下文 + 当前问题）
        AiPrompt prompt = aiPromptService.buildPrompt(message, pageContext, sessionId);
        appendExtraPromptContext(prompt,extraPromptContext);  //将第一次模型回复的拼进prompt

        String actionResultPromptContext = buildActionResultPromptContext(navigateFromIntent);
        appendExtraPromptContext(prompt,actionResultPromptContext);

        boolean articleDetailQa = isArticleDetailQa(intent);
        ArticleRagContext currentArticleReferenceFromIntent = buildCurrentArticleReferenceFromIntent(intent, pageContext);

        List<ArticleRagContext> ragContexts;
        if(articleDetailQa){
            ragContexts = currentArticleReferenceFromIntent == null ? List.of() : List.of(currentArticleReferenceFromIntent);
        }
        else{
            ArticleRagSearchResult ragSearchResult = articleRagSearchService.search(message, intent);
            ragContexts = ragSearchResult.contexts();

            appendRagContextToPrompt(prompt, ragContexts);
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

        String requestId = UUID.randomUUID().toString();
        Flux<AiChatEventVO> dataEvents = aiModelService.streamChat(prompt,requestId)
                .doOnNext(fullReply::append)
                .map(chunk -> AiChatEventVO.builder()
                        .eventType(AiChatEventType.DATA.getValue())
                        .eventData(chunk)
                        .build());

        Mono<AiChatEventVO> stopEvent = Mono.fromSupplier(() -> {
            AiMessages assistantMessage = saveStreamAssistantMessage(sessionId,userId,rawPageContextJson, fullReply.toString());

            // 异步提取候选记忆（规则预筛命中才写入，不会阻塞 SSE）
            aiMemoryCandidateExtractorService.extractAfterChat(
                    userId,
                    sessionId,
                    userMessage.getId(),
                    message,
                    fullReply.toString()
            );

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
                navigate = getNavigateOrInfer(requestId, message);
            }
            if (navigate != null) {
                eventData.put("navigate", navigate);
            }

            AiEditorCommand editorAction = editorActionFromIntent;

            if (editorAction == null) {
                editorAction = getEditorActionOrInfer(requestId, message);
            }
            // fillArticle 只能从 Workflow 的 editorAction 出来，普通聊天不允许直接填充文章
            if (editorAction != null && "fillArticle".equals(editorAction.getType())) {
                editorAction = null;
            }
            if(editorAction != null){
                eventData.put("editorAction", editorAction);
            }

            AiArticleActionCommand articleAction = articleActionFromIntent;
            // 非文章意图不取 tool registry，避免主模型顺手调 tool 串扰
            if (articleAction == null
                    && !"EDITOR_ACTION".equals(intent.getIntent())
                    && !"NAVIGATE".equals(intent.getIntent())) {
                articleAction = getArticleActionOrInfer(requestId, message, pageContext);
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

    /** 从意图分类结果创建文章 Workflow，绑定到当前 session */
    /** 统一入口判断：新意图 CREATE_ARTICLE_WORKFLOW，或历史兼容 EDITOR_ACTION + fillArticle */
    private boolean isCreateArticleWorkflowIntent(AiIntent intent) {
        if (intent == null) {
            return false;
        }
        if ("CREATE_ARTICLE_WORKFLOW".equals(intent.getIntent())) {
            return true;
        }
        return "EDITOR_ACTION".equals(intent.getIntent())
                && "fillArticle".equals(intent.getActionType());
    }

    /** 意图分类 + 确定性兜底：LLM 分类不稳定，明显的写文章请求即使分错也强制走 Workflow（内部还有需求分析/澄清） */
    private AiIntent classifyWithFallback(String message, PageContextDTO pageContext) {
        AiIntent intent = aiIntentClassifier.classify(message, pageContext);
        if (!isCreateArticleWorkflowIntent(intent) && looksLikeCreateArticleRequest(message)) {
            log.info("意图分类兜底命中：\"{}\" 强制走 CREATE_ARTICLE_WORKFLOW", message);
            intent = buildCreateArticleWorkflowIntent(message);
        }
        return intent;
    }

    private boolean looksLikeCreateArticleRequest(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String text = message.trim();
        return text.matches(".*(帮我|给我|请|生成|写|创作).*(一篇|篇).*(文章|博客|博文|草稿|大纲).*")
                || text.matches(".*(写一篇|生成一篇|创作一篇).*(文章|博客|博文).*");
    }

    private AiIntent buildCreateArticleWorkflowIntent(String message) {
        AiIntent intent = new AiIntent();
        intent.setIntent("CREATE_ARTICLE_WORKFLOW");
        intent.setTopic(message);
        return intent;
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

        // 用 intent.topic 拼 requirement，优先级高于原始 message
        String requirement = intent.getTopic() != null && !intent.getTopic().isBlank()
                ? intent.getTopic()
                : message;
        if (intent.getRequirements() != null && !intent.getRequirements().isBlank()) {
            requirement += "，要求：" + intent.getRequirements();
        }

        AiWorkflowCreateArticleDTO workflowDTO = new AiWorkflowCreateArticleDTO();
        workflowDTO.setConversationId(sessionId);
        workflowDTO.setRequirement(requirement);
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
                        AiWorkflowStepEmitter emitter = new AiWorkflowStepEmitter() {
                            @Override
                            public void emit(String step, String status, String stepMessage) {
                                // 初次创建时 step 事件可以先不推给前端
                            }

                            @Override
                            public void emitContent(String step, String field, String delta) {
                                if ("outline".equals(field)) {
                                    sink.next(AiChatEventVO.builder()
                                            .eventType(AiChatEventType.DATA.getValue())
                                            .eventData(delta)
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
                        sink.error(e);
                    } finally {
                        UserContext.clear();
                    }
                })
        );

        return Flux.concat(Flux.just(paramEvent), workflowEvents);
    }

    private String buildWorkflowAssistantContent(AiWorkflowRunVO workflow) {
        Object contextValue = workflow == null ? null : workflow.getContext();
        if (!(contextValue instanceof Map<?, ?> context)) {
            return "已创建文章创作 Workflow，请先确认生成方案。";
        }

        Object outline = context.get("outline");
        if (outline == null || String.valueOf(outline).isBlank()) {
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

        // WAITING_OUTLINE_CONFIRM / WAITING_DRAFT_CONFIRM / WAITING_FILL_CONFIRM
        // 这些状态下的确认操作应走前端 approve/reject 按钮，普通输入提示用户
        if (status == AiWorkflowStatus.WAITING_OUTLINE_CONFIRM
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

        // WAITING_USER_SAVE：提示用户在编辑器保存/发布
        if (status == AiWorkflowStatus.WAITING_USER_SAVE) {
            AiMessages assistantMessage = saveStreamAssistantMessage(
                    sessionId, userId, rawPageContextJson,
                    "文章已填充到编辑器，请在编辑器中保存草稿或发布文章，然后点击底部的「已保存 / 发布」按钮完成 Workflow。",
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
        AiIntent intent = classifyWithFallback(message, pageContext);

        // 重新检查是否需要起新 Workflow（topic 已由兜底填充，非空才起）
        if (isCreateArticleWorkflowIntent(intent)
                && intent.getTopic() != null && !intent.getTopic().isBlank()) {
            // 删除已保存的 userMessage（新 Workflow 方法会重新保存）
            removeById(userMessage.getId());
            return streamCreateArticleWorkflowFromIntent(
                    message, intent, pageContext, rawPageContextJson, userId, sessionId);
        }

        // 否则走普通 SSE 聊天（复用流式逻辑，但 userMessage 已经保存了）
        AiPrompt prompt = aiPromptService.buildPrompt(message, pageContext, sessionId);
        StringBuilder fullReply = new StringBuilder();

        AiSessions updatedSession = getOwnedSession(sessionId, userId);

        AiChatEventVO paramEvent = AiChatEventVO.builder()
                .eventType(AiChatEventType.PARAM.getValue())
                .eventData(Map.of(
                        "session", AiSessionVO.from(updatedSession),
                        "userMessage", AiMessageVO.from(userMessage)
                ))
                .build();

        String requestId = UUID.randomUUID().toString();
        Flux<AiChatEventVO> dataEvents = aiModelService.streamChat(prompt, requestId)
                .doOnNext(fullReply::append)
                .map(chunk -> AiChatEventVO.builder()
                        .eventType(AiChatEventType.DATA.getValue())
                        .eventData(chunk)
                        .build());

        Mono<AiChatEventVO> stopEvent = Mono.fromSupplier(() -> {
            AiMessages assistantMessage = saveStreamAssistantMessage(
                    sessionId, userId, rawPageContextJson, fullReply.toString());

            AiSessions updated = getOwnedSession(sessionId, userId);

            Map<String, Object> eventData = new HashMap<>();
            eventData.put("session", AiSessionVO.from(updated));
            eventData.put("assistantMessage", AiMessageVO.from(assistantMessage));
            eventData.put("references", List.of());

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
        assistantMessage.setWorkflowRunId(String.valueOf(workflowRunId));
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

        // 先用 LLM 分类意图（游客同样先分类再决定，带确定性兜底）
        AiIntent intent = classifyWithFallback(message, pageContext);

        // 创建文章意图 → 有主题提示登录，没主题追问
        if (isCreateArticleWorkflowIntent(intent)) {
            AiMessageVO assistantMessage = new AiMessageVO();
            assistantMessage.setId("guest-ai-" + System.currentTimeMillis());
            assistantMessage.setSessionId("guest");
            assistantMessage.setRole("assistant");
            if (intent.getTopic() != null && !intent.getTopic().isBlank()) {
                assistantMessage.setContent("文章创作 Workflow 需要登录后使用，请先登录后再试。");
            } else {
                assistantMessage.setContent("可以，想写什么主题？\n比如 Redis 缓存、Kafka 消息队列、RAG 检索增强这些");
            }
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

        AiArticleActionCommand articleActionFromIntent = buildArticleActionFromIntent(intent, pageContext);
        String extraPromptContext = buildExtraPromptContextFromIntent(intent, pageContext);
        if (extraPromptContext == null || extraPromptContext.isBlank()) {
            extraPromptContext = buildArticleDetailContextFromIntent(intent, pageContext);
        }

        AiNavigateCommand navigateFromIntent = buildNavigateFromIntent(intent,pageContext);
        AiEditorCommand editorActionFromIntent = buildEditorActionFromIntent(intent,message);

        // 拼 prompt（游客无 sessionId，跳过历史记忆）
        AiPrompt prompt = aiPromptService.buildPrompt(message, pageContext, null);
        appendExtraPromptContext(prompt, extraPromptContext);

        String actionResultPromptContext = buildActionResultPromptContext(navigateFromIntent);
        appendExtraPromptContext(prompt,actionResultPromptContext);

        boolean articleDetailQa = isArticleDetailQa(intent);
        ArticleRagContext currentArticleReference = buildCurrentArticleReferenceFromIntent(intent, pageContext);

        List<ArticleRagContext> ragContexts;
        if (articleDetailQa) {
            ragContexts = currentArticleReference == null ? List.of() : List.of(currentArticleReference);
        } else {
            ArticleRagSearchResult ragSearchResult = articleRagSearchService.search(message, intent);
            ragContexts = ragSearchResult.contexts();

            appendRagContextToPrompt(prompt, ragContexts);
        }


        AiChatEventVO paramEvent = AiChatEventVO.builder()
                .eventType(AiChatEventType.PARAM.getValue())
                .eventData(Map.of(
                        "session", guestSession,
                        "userMessage", userMessage
                ))
                .build();

        String requestId = UUID.randomUUID().toString();
        Flux<AiChatEventVO> dataEvents = aiModelService.streamChat(prompt,requestId)
                .doOnNext(fullReply::append)
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
                navigate = getNavigateOrInfer(requestId, message);
            }
            if (navigate != null) {
                eventData.put("navigate", navigate);
            }

            AiEditorCommand editorAction = editorActionFromIntent;
            if (editorAction == null) {
                editorAction = getEditorActionOrInfer(requestId, message);
            }
            // fillArticle 只能从 Workflow 的 editorAction 出来，普通聊天不允许直接填充文章
            if (editorAction != null && "fillArticle".equals(editorAction.getType())) {
                editorAction = null;
            }
            if(editorAction != null){
                eventData.put("editorAction", editorAction);
            }

            AiArticleActionCommand articleAction = articleActionFromIntent;
            // 非文章意图不取 tool registry，避免主模型顺手调 tool 串扰
            if (articleAction == null
                    && !"EDITOR_ACTION".equals(intent.getIntent())
                    && !"NAVIGATE".equals(intent.getIntent())) {
                articleAction = getArticleActionOrInfer(requestId, message, pageContext);
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
        ).doFinally(signalType -> aiToolActionRegistry.clear(requestId));
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

    private AiNavigateCommand getNavigateOrInfer(String requestId, String message) {
        AiNavigateCommand navigate = aiToolActionRegistry.getNavigate(requestId);
        if (navigate != null) {
            log.info("AI导航指令来自工具调用: requestId={}, target={}, param={}",
                    requestId, navigate.getTarget(), navigate.getParam());
            return navigate;
        }

        navigate = inferNavigateFromMessage(message);
        if (navigate != null) {
            log.info("AI导航指令来自后端兜底: requestId={}, target={}, param={}",
                    requestId, navigate.getTarget(), navigate.getParam());
        } else {
            log.info("本轮AI无导航指令: requestId={}", requestId);
        }
        return navigate;
    }

    private AiNavigateCommand inferNavigateFromMessage(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }

        String text = message.replaceAll("\\s+", "");
        boolean hasNavigationIntent = containsAny(text,
                "跳转", "打开", "进入", "去", "回到", "返回", "前往", "切到", "带我", "查看", "看看");

        if (!hasNavigationIntent && text.length() > 12) {
            return null;
        }

        if (containsAny(text, "草稿箱", "我的草稿", "草稿")) {
            return new AiNavigateCommand("drafts", null);
        }
        if (containsAny(text, "写文章", "新建文章", "编辑器")) {
            return new AiNavigateCommand("editor", null);
        }
        if (containsAny(text, "个人中心", "我的主页", "我的个人主页")) {
            return new AiNavigateCommand("profile", null);
        }
        if (containsAny(text, "热门排行", "热门榜", "排行榜", "热度榜")) {
            return new AiNavigateCommand("hotRank", null);
        }

        Matcher userMatcher = NAV_USER_ID_PATTERN.matcher(text);
        if (userMatcher.find()) {
            return new AiNavigateCommand("userProfile", userMatcher.group(1));
        }

        Matcher articleMatcher = NAV_ARTICLE_ID_PATTERN.matcher(text);
        if (articleMatcher.find()) {
            return new AiNavigateCommand("article", articleMatcher.group(1));
        }

        if (containsAny(text, "首页", "主页")) {
            return new AiNavigateCommand("home", null);
        }

        return null;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private AiEditorCommand getEditorActionOrInfer(String requestId, String message) {
        AiEditorCommand editorAction = aiToolActionRegistry.getEditor(requestId);
        if (editorAction != null) {
            log.info("AI编辑器指令来自工具调用: requestId={}, type={}", requestId, editorAction.getType());
            return editorAction;
        }

        editorAction = inferEditorActionFromMessage(message);
        if (editorAction != null) {
            log.info("AI编辑器指令来自后端兜底: requestId={}, type={}", requestId, editorAction.getType());
        } else {
            log.info("本轮AI无编辑器指令: requestId={}", requestId);
        }
        return editorAction;
    }

    private AiEditorCommand inferEditorActionFromMessage(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }

        String text = message.replaceAll("\\s+", "");
        if (containsAny(text, "保存草稿", "存草稿", "保存到草稿箱", "存到草稿箱",
                "保存文章", "帮我保存", "保存一下", "保存")) {
            AiEditorCommand command = new AiEditorCommand();
            command.setType("saveDraft");
            return command;
        }

        if (containsAny(text, "发布文章", "发布这篇", "帮我发布", "直接发布", "发布")) {
            AiEditorCommand command = new AiEditorCommand();
            command.setType("publish");
            return command;
        }

        return null;
    }


    //关于文章详情页内的ai调用tool兜底
    private AiArticleActionCommand getArticleActionOrInfer(
            String requestId,
            String message,
            PageContextDTO pageContext
    ) {
        AiArticleActionCommand action = aiToolActionRegistry.getArticleAction(requestId);
        if (action != null) {
            log.info("AI文章动作来自工具调用: requestId={}, type={}, articleId={}",
                    requestId, action.getType(), action.getArticleId());
            return action;
        }
        action = inferArticleActionFromMessage(message, pageContext);
        if (action != null) {
            log.info("AI文章动作来自后端兜底: requestId={}, type={}, articleId={}",
                    requestId, action.getType(), action.getArticleId());
        } else {
            log.info("本轮AI无文章动作: requestId={}", requestId);
        }

        return action;
    }

    private AiArticleActionCommand inferArticleActionFromMessage(String message, PageContextDTO pageContext) {
        if (message == null || message.isBlank()) {
            return null;
        }

        if (pageContext == null
                || !"article-detail".equals(pageContext.getPageType())
                || pageContext.getArticleId() == null
                || pageContext.getArticleId().isBlank()) {
            return null;
        }

        String text = message.replaceAll("\\s+", "");

        String type = null;

        if (containsAny(text, "取消点赞", "不点赞", "取消喜欢")) {
            type = "unlikeArticle";
        } else if (containsAny(text, "点赞", "喜欢这篇", "给这篇点赞")) {
            type = "likeArticle";
        } else if (containsAny(text, "取消收藏", "移出收藏", "不收藏")) {
            type = "unfavoriteArticle";
        } else if (containsAny(text, "收藏", "加入收藏")) {
            type = "favoriteArticle";
        } else if (containsAny(text, "评论区", "看评论", "查看评论", "跳到评论", "去评论区", "带我去评论区")) {
            type = "scrollToComments";
        } else if (containsAny(text, "分享", "复制链接", "文章链接", "把链接发给我")) {
            type = "copyArticleLink";
        } else if (containsAny(text, "取消关注", "不再关注", "取关")) {
            type = "unfollowAuthor";
        } else if (containsAny(text, "关注作者", "关注这个作者", "关注一下作者","关注他","关注该作者")) {
            type = "followAuthor";
        }

        if (type == null) {
            return null;
        }

        AiArticleActionCommand command = new AiArticleActionCommand();
        command.setType(type);
        command.setArticleId(pageContext.getArticleId());
        return command;
    }


    //第一个：根据意图生成文章动作。
    private AiArticleActionCommand buildArticleActionFromIntent(AiIntent intent,PageContextDTO pageContext){
        if(intent == null || !"ARTICLE_ACTION".equals(intent.getIntent())){
            return null;
        }

        String articleId = intent.getArticleId();
        if ((articleId == null || articleId.isBlank())
                && pageContext != null
                && pageContext.getArticleId() != null
                && !pageContext.getArticleId().isBlank()) {
            articleId = pageContext.getArticleId();
        }

        if (articleId == null || articleId.isBlank()) {
            return null;
        }

        if (intent.getActionType() == null || intent.getActionType().isBlank()) {
            return null;
        }

        AiArticleActionCommand command = new AiArticleActionCommand();
        command.setType(intent.getActionType());
        command.setArticleId(articleId);
        command.setContent(intent.getContent());

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
    private AiNavigateCommand buildNavigateFromIntent(AiIntent intent,PageContextDTO pageContext) {
        if (intent == null || !"NAVIGATE".equals(intent.getIntent())) {
            return null;
        }

        String target = intent.getTarget();
        String param = intent.getParam();

        if("userProfile".equals(target)){
            param = resolveAuthorIdByArticleId(intent, pageContext);
        }


        if (target == null || target.isBlank()) {
            return null;
        }
        if (param != null && param.isBlank()) {
            param = null;
        }

        return new AiNavigateCommand(target, param);
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
    private AiEditorCommand buildEditorActionFromIntent(AiIntent intent,String message) {
        if (intent == null || !"EDITOR_ACTION".equals(intent.getIntent())) {
            return null;
        }

        String actionType = intent.getActionType();
        if (actionType == null || actionType.isBlank()) {
            return null;
        }

        if ("saveDraft".equals(actionType) || "publish".equals(actionType)) {
            AiEditorCommand command = new AiEditorCommand();
            command.setType(actionType);
            return command;
        }

        if ("fillArticle".equals(actionType)) {
            return aiEditorDraftGenerator.generateDraft(message, intent);
        }

        return null;
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

    //在当前文章详情页回答问题时，不走全站RAG
    private boolean isArticleDetailQa(AiIntent intent){
        return intent != null && "ARTICLEDETAIL_QA".equals(intent.getIntent());
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
    private ArticleRagContext buildCurrentArticleReferenceFromIntent(AiIntent intent,PageContextDTO pageContext){
        if(!isArticleDetailQa(intent)){
            return null;
        }

        String articleId = resolveArticleIdFromIntent(intent, pageContext);
        if(articleId == null || articleId.isBlank()){
            return null;
        }

        Long id;
        try{
            id = Long.valueOf(articleId);
        }
        catch (Exception e){
            return null;
        }

        Articles article = articlesService.lambdaQuery()
                .select(
                        Articles::getId,
                        Articles::getTitle,
                        Articles::getSummary
                )
                .eq(Articles::getId,id)
                .eq(Articles::getStatus,BlogConstants.ArticlesStatus.PUBLISHED)
                .one();

        if(article == null){
            return null;
        }

        String snippet = article.getSummary();
        if (snippet == null || snippet.isBlank()) {
            snippet = article.getTitle();
        }

        return new ArticleRagContext(
                article.getId(),
                article.getTitle(),
                0,
                snippet
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
}
