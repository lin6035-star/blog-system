package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.errorprone.annotations.Var;
import com.hailin.blogsystem.ai.rag.ArticleRagPromptBuilder;
import com.hailin.blogsystem.ai.rag.ArticleRagRetrieveService;
import com.hailin.blogsystem.ai.rag.ArticleRagSearchService;
import com.hailin.blogsystem.ai.tool.AiToolActionRegistry;
import com.hailin.blogsystem.ai.tool.AiUserProfileTools;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.*;
import com.hailin.blogsystem.entity.dto.*;
import com.hailin.blogsystem.entity.vo.*;
import com.hailin.blogsystem.mapper.AiMessageMapper;
import com.hailin.blogsystem.mapper.AiSessionMapper;
import com.hailin.blogsystem.service.*;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

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
    private final ArticleRagRetrieveService articleRagRetrieveService;
    private final ArticleRagPromptBuilder articleRagPromptBuilder;
    private final ArticleRagSearchService articleRagSearchService;

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

    // ==================== 以下为非流式接口，前端已全面切到流式，暂时注释，后续删除 ====================

    // @Override  //2.发送聊天消息
    // @Transactional
    // public AiChatVO chat(AiChatDTO aiChatDTO) {
    //     Long userId = UserContext.get();  //1. 从 UserContext 拿 userId（null = 游客）
    //
    //     if (aiChatDTO == null) { //校验message为非空
    //         throw new IllegalArgumentException("请求参数不能为空");
    //     }
    //     String message = aiChatDTO.getMessage() == null ? "" : aiChatDTO.getMessage().trim();
    //     if (message.isEmpty()) {
    //         throw new IllegalArgumentException("消息内容不能为空");
    //     }
    //
    //     //准备两个context
    //     //rawPageContextJson 还是存数据库，表示用户当时在哪个页面问的。
    //     String rawPageContextJson = toJson(aiChatDTO.getPageContext());
    //
    //     // ===== 游客模式：调 AI，不存库 =====
    //     if (userId == null) {  //如果 userId == null → chatAsGuest()（只拼 VO，不存库）
    //         return chatAsGuest(message, rawPageContextJson, aiChatDTO.getPageContext());
    //     }
    //
    //     // ===== 用户模式：调 AI，要存库 =====
    //     AiSessionVO sessionVO;
    //     Long sessionId;
    //
    //     //没传 sessionId → 新建会话（标题取 message 前 20 字）
    //     if(aiChatDTO.getSessionId() == null || aiChatDTO.getSessionId().trim().isEmpty()){
    //         AiCreateSessionDTO aiCreateSessionDTO = new AiCreateSessionDTO();
    //         aiCreateSessionDTO.setTitle(buildSessionTitle(message));
    //
    //         sessionVO = aiSessionService.createSession(aiCreateSessionDTO);
    //         sessionId = Long.valueOf(sessionVO.getId());
    //     }
    //     else{
    //         sessionId = Long.valueOf(aiChatDTO.getSessionId());
    //         AiSessions session = getOwnedSession(sessionId, userId);
    //     }
    //
    //     // 拼完整 prompt（含历史记忆 + 页面上下文 + 当前问题）
    //     AiPrompt prompt = aiPromptService.buildPrompt(message, aiChatDTO.getPageContext(), sessionId);
    //
    //
    //
    //     LocalDateTime now = LocalDateTime.now();
    //
    //     //存用户消息到 ai_messages
    //     AiMessages userMessage = new AiMessages();
    //     userMessage.setSessionId(sessionId);
    //     userMessage.setRole(ROLE_USER);
    //     userMessage.setContent(message);
    //     userMessage.setPageContext(rawPageContextJson);
    //     userMessage.setCreatedAt(now);
    //     save(userMessage);
    //
    //
    //     //调 aiModelService.chat()  ← ⭐核心，阻塞等 AI 回复
    //     String requestId = UUID.randomUUID().toString();
    //     String reply;
    //     AiNavigateCommand navigate;
    //     AiEditorCommand editorCommand;
    //     try {
    //         reply = aiModelService.chat(prompt, requestId);
    //         navigate = getNavigateOrInfer(requestId, message);
    //         editorCommand = getEditorActionOrInfer(requestId, message);
    //     } finally {
    //         aiToolActionRegistry.clear(requestId);
    //     }
    //
    //     //存ai回复到ai_message
    //     AiMessages assistantMessage = new AiMessages();
    //     assistantMessage.setSessionId(sessionId);
    //     assistantMessage.setRole(ROLE_ASSISTANT);
    //     assistantMessage.setContent(reply);
    //     assistantMessage.setPageContext(rawPageContextJson);
    //     assistantMessage.setCreatedAt(LocalDateTime.now());
    //     save(assistantMessage);
    //
    //     //更新ai_sessions.updated_at
    //     aiSessionService.lambdaUpdate()
    //             .eq(AiSessions::getId, sessionId)
    //             .eq(AiSessions::getUserId, userId)
    //             .set(AiSessions::getUpdatedAt, LocalDateTime.now())
    //             .update();
    //
    //     AiSessions updatedSession = getOwnedSession(sessionId, userId);
    //
    //     //拼 AiChatVO 返回
    //     AiChatVO aiChatVO = new AiChatVO();
    //     aiChatVO.setSession(AiSessionVO.from(updatedSession));
    //     aiChatVO.setUserMessage(AiMessageVO.from(userMessage));
    //     aiChatVO.setAssistantMessage(AiMessageVO.from(assistantMessage));
    //     aiChatVO.setNavigate(navigate);
    //     aiChatVO.setEditorAction(editorCommand);
    //     return aiChatVO;
    //
    // }
    //获取当前用户的会话
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
    // private AiChatVO chatAsGuest(String message, String rawPageContextJson, PageContextDTO pageContext) {
    //     LocalDateTime now = LocalDateTime.now();
    //
    //     // 1. 组装临时 userMessage（纯 VO，不 new AiMessages，不 save）
    //     AiMessageVO userMsg = new AiMessageVO();
    //     userMsg.setId("guest-user-" + System.currentTimeMillis());
    //     userMsg.setSessionId("guest-session");
    //     userMsg.setRole("user");
    //     userMsg.setContent(message);
    //     userMsg.setPageContext(rawPageContextJson);
    //     userMsg.setCreatedAt(now);
    //
    //     // 2. 拼 prompt（游客无 sessionId，跳过历史记忆）
    //     AiPrompt prompt = aiPromptService.buildPrompt(message, pageContext, null);
    //
    //     // 3. 调 AI
    //     String requestId = UUID.randomUUID().toString();
    //     String reply;
    //     AiNavigateCommand navigate;
    //     AiEditorCommand aiEditorCommand;
    //     try {
    //         reply = aiModelService.chat(prompt, requestId);
    //         navigate = getNavigateOrInfer(requestId, message);
    //         aiEditorCommand = getEditorActionOrInfer(requestId, message);
    //     } finally {
    //         aiToolActionRegistry.clear(requestId);
    //     }
    //
    //     // 4. 组装临时 assistantMessage
    //     AiMessageVO assistantMsg = new AiMessageVO();
    //     assistantMsg.setId("guest-ai-" + System.currentTimeMillis());
    //     assistantMsg.setSessionId("guest-session");
    //     assistantMsg.setRole("assistant");
    //     assistantMsg.setContent(reply);
    //     assistantMsg.setPageContext(rawPageContextJson);
    //     assistantMsg.setCreatedAt(LocalDateTime.now());
    //
    //     // 4. 组装临时 session
    //     AiSessionVO sessionVO = new AiSessionVO();
    //     sessionVO.setId("guest-session");
    //     sessionVO.setTitle(buildSessionTitle(message));
    //     sessionVO.setCreatedAt(now);
    //     sessionVO.setUpdatedAt(now);
    //
    //     // 5. 打包返回
    //     AiChatVO result = new AiChatVO();
    //     result.setSession(sessionVO);
    //     result.setUserMessage(userMsg);
    //     result.setAssistantMessage(assistantMsg);
    //     result.setNavigate(navigate);
    //     result.setEditorAction(aiEditorCommand);
    //     return result;
    // }

    // ==================== 以上为非流式接口 ====================


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

        AiIntent intent = aiIntentClassifier.classify(message,pageContext);
        AiArticleActionCommand articleActionFromIntent = buildArticleActionFromIntent(intent, pageContext);
        String extraPromptContext = buildExtraPromptContextFromIntent(intent, pageContext);
        if (extraPromptContext == null || extraPromptContext.isBlank()) {
            extraPromptContext = buildArticleDetailContextFromIntent(intent, pageContext);
        }
        /*if (extraPromptContext == null || extraPromptContext.isBlank()) {
            extraPromptContext = buildArticleSearchContextFromIntent(intent);
        }*/
        
        
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

        AiSessions session = getOwnedSession(sessionId, userId);
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
    //保存停止生成的内容
    private AiMessages saveStreamAssistantMessage(
            Long sessionId,
            Long userId,
            String rawPageContextJson,
            String content
    ){
        AiMessages assistantMessage = new AiMessages();
        assistantMessage.setSessionId(sessionId);
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

        AiIntent intent = aiIntentClassifier.classify(message, pageContext);
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

    /*private boolean isArticleSearch(AiIntent intent) {
        return intent != null && "ARTICLE_SEARCH".equals(intent.getIntent());
    }*/
    
    //关于搜索站内文章列表
    /*private String buildArticleSearchContextFromIntent(AiIntent intent){
        if(intent == null || !"ARTICLE_SEARCH".equals(intent.getIntent())){
            return null;
        }

        String keyword = intent.getKeyWord();
        if (keyword == null || keyword.isBlank()) {
            return "用户想搜索站内文章，但缺少搜索关键词。";
        }

        PageVO<ArticleDetailVO> pageResult = articlesService.getArticles(1L, 3L, intent.getKeyWord(), null, "lasted");
        List<ArticleDetailVO> list = pageResult.getList();

        StringBuilder sb = new StringBuilder();
        sb.append("站内文章搜索结果：\n");
        sb.append("搜索关键词：").append(keyword).append("\n");
        sb.append("结果总数：").append(pageResult.getTotal()).append("\n\n");

        if (list == null || list.isEmpty()) {
            sb.append("没有找到匹配的公开文章。\n");
            sb.append("请告诉用户暂时没有找到相关文章，不要编造不存在的文章。");
            return sb.toString();
        }

        sb.append("最多展示前 3 篇：\n");

        for (int i = 0; i < list.size(); i++) {
            ArticleDetailVO article = list.get(i);

            sb.append(i + 1).append(". ");
            sb.append("文章ID：").append(article.getId()).append("\n");
            sb.append("标题：").append(article.getTitle()).append("\n");

            if (article.getSummary() != null && !article.getSummary().isBlank()) {
                sb.append("摘要：").append(article.getSummary()).append("\n");
            }

            sb.append("浏览数：").append(article.getViewCount()).append("\n");
            sb.append("点赞数：").append(article.getLikeCount()).append("\n");
            sb.append("收藏数：").append(article.getFavoriteCount()).append("\n");
            sb.append("评论数：").append(article.getCommentCount()).append("\n");
            sb.append("\n");
        }

        sb.append("请基于以上搜索结果回答用户。");
        sb.append("如果用户想打开某篇文章，请直接调用 goToArticle 导航工具，传入对应文章ID，不要只让用户自己输入文章ID。");
        sb.append("不要编造搜索结果之外的文章。");

        return sb.toString();
    }
    private List<ArticleRagContext> buildArticleSearchFallbackContextsFromIntent(AiIntent intent){
        if(!isArticleSearch(intent)){
            return List.of();
        }

        String keyWord = intent.getKeyWord();
        if (keyWord == null || keyWord.isBlank()) {
            return List.of();
        }
        PageVO<ArticleDetailVO> pageResult = articlesService.getArticles(1L, 3L, keyWord, null, "lasted");
        List<ArticleDetailVO> list = pageResult.getList();

        if (list == null || list.isEmpty()) {
            log.info("ARTICLE_SEARCH MySQL 兜底无结果，keyword={}", keyWord);
            return List.of();
        }

        log.info("ARTICLE_SEARCH 使用 MySQL 兜底生成引用，keyword={}, count={}", keyWord, list.size());

        return list.stream()
                .filter(article -> article.getId() != null)
                .map(article -> new ArticleRagContext(
                        article.getId(),
                        article.getTitle(),
                        0,
                        buildArticleSearchFallbackSnippet(article)
                ))
                .toList();
    }*/
    /*private String buildArticleSearchFallbackSnippet(ArticleDetailVO article) {
        if (article == null) {
            return "";
        }

        if (article.getSummary() != null && !article.getSummary().isBlank()) {
            return article.getSummary();
        }

        if (article.getContent() != null && !article.getContent().isBlank()) {
            return limitText(article.getContent(), 50);
        }

        return article.getTitle() == null ? "" : article.getTitle();
    }*/

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

    //有关RAG检索时的文章来源
    /*private List<ArticleRagContext> retrieveArticleRagContexts(String message){
        try{
            return articleRagRetrieveService.retrieve(message);
        }
        catch (Exception e){
            log.warn("RAG 检索失败，降级为普通 AI 对话", e);
            return List.of();
        }
    }*/
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
}
