package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.tool.AiToolActionRegistry;
import com.hailin.blogsystem.entity.*;
import com.hailin.blogsystem.entity.dto.AiChatDTO;
import com.hailin.blogsystem.entity.dto.AiCreateSessionDTO;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.entity.vo.AiChatEventVO;
import com.hailin.blogsystem.entity.vo.AiChatVO;
import com.hailin.blogsystem.entity.vo.AiMessageVO;
import com.hailin.blogsystem.entity.vo.AiSessionVO;
import com.hailin.blogsystem.mapper.AiMessageMapper;
import com.hailin.blogsystem.mapper.AiSessionMapper;
import com.hailin.blogsystem.service.AiMessageService;
import com.hailin.blogsystem.service.AiPromptService;
import com.hailin.blogsystem.service.AiSessionService;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.hailin.blogsystem.service.AiModelService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    @Override  //2.发送聊天消息
    @Transactional
    public AiChatVO chat(AiChatDTO aiChatDTO) {
        Long userId = UserContext.get();  //1. 从 UserContext 拿 userId（null = 游客）

        if (aiChatDTO == null) { //校验message为非空
            throw new IllegalArgumentException("请求参数不能为空");
        }
        String message = aiChatDTO.getMessage() == null ? "" : aiChatDTO.getMessage().trim();
        if (message.isEmpty()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }

        //准备两个context
        //rawPageContextJson 还是存数据库，表示用户当时在哪个页面问的。
        String rawPageContextJson = toJson(aiChatDTO.getPageContext());

        // ===== 游客模式：调 AI，不存库 =====
        if (userId == null) {  //如果 userId == null → chatAsGuest()（只拼 VO，不存库）
            return chatAsGuest(message, rawPageContextJson, aiChatDTO.getPageContext());
        }

        // ===== 用户模式：调 AI，要存库 =====
        AiSessionVO sessionVO;
        Long sessionId;

        //没传 sessionId → 新建会话（标题取 message 前 20 字）
        if(aiChatDTO.getSessionId() == null || aiChatDTO.getSessionId().trim().isEmpty()){
            AiCreateSessionDTO aiCreateSessionDTO = new AiCreateSessionDTO();
            aiCreateSessionDTO.setTitle(buildSessionTitle(message));

            sessionVO = aiSessionService.createSession(aiCreateSessionDTO);
            sessionId = Long.valueOf(sessionVO.getId());
        }
        else{
            sessionId = Long.valueOf(aiChatDTO.getSessionId());
            AiSessions session = getOwnedSession(sessionId, userId);
        }

        // 拼完整 prompt（含历史记忆 + 页面上下文 + 当前问题）
        AiPrompt prompt = aiPromptService.buildPrompt(message, aiChatDTO.getPageContext(), sessionId);



        LocalDateTime now = LocalDateTime.now();

        //存用户消息到 ai_messages
        AiMessages userMessage = new AiMessages();
        userMessage.setSessionId(sessionId);
        userMessage.setRole(ROLE_USER);
        userMessage.setContent(message);
        userMessage.setPageContext(rawPageContextJson);
        userMessage.setCreatedAt(now);
        save(userMessage);


        //调 aiModelService.chat()  ← ⭐核心，阻塞等 AI 回复
        String requestId = UUID.randomUUID().toString();
        String reply;
        AiNavigateCommand navigate;
        AiEditorCommand editorCommand;
        try {
            reply = aiModelService.chat(prompt, requestId);
            navigate = getNavigateOrInfer(requestId, message);
            editorCommand = getEditorActionOrInfer(requestId, message);
        } finally {
            aiToolActionRegistry.clear(requestId);
        }

        //存ai回复到ai_message
        AiMessages assistantMessage = new AiMessages();
        assistantMessage.setSessionId(sessionId);
        assistantMessage.setRole(ROLE_ASSISTANT);
        assistantMessage.setContent(reply);
        assistantMessage.setPageContext(rawPageContextJson);
        assistantMessage.setCreatedAt(LocalDateTime.now());
        save(assistantMessage);

        //更新ai_sessions.updated_at
        aiSessionService.lambdaUpdate()
                .eq(AiSessions::getId, sessionId)
                .eq(AiSessions::getUserId, userId)
                .set(AiSessions::getUpdatedAt, LocalDateTime.now())
                .update();

        AiSessions updatedSession = getOwnedSession(sessionId, userId);

        //拼 AiChatVO 返回
        AiChatVO aiChatVO = new AiChatVO();
        aiChatVO.setSession(AiSessionVO.from(updatedSession));
        aiChatVO.setUserMessage(AiMessageVO.from(userMessage));
        aiChatVO.setAssistantMessage(AiMessageVO.from(assistantMessage));
        aiChatVO.setNavigate(navigate);
        aiChatVO.setEditorAction(editorCommand);
        return aiChatVO;

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
    private AiChatVO chatAsGuest(String message, String rawPageContextJson, PageContextDTO pageContext) {
        LocalDateTime now = LocalDateTime.now();

        // 1. 组装临时 userMessage（纯 VO，不 new AiMessages，不 save）
        AiMessageVO userMsg = new AiMessageVO();
        userMsg.setId("guest-user-" + System.currentTimeMillis());
        userMsg.setSessionId("guest-session");
        userMsg.setRole("user");
        userMsg.setContent(message);
        userMsg.setPageContext(rawPageContextJson);
        userMsg.setCreatedAt(now);

        // 2. 拼 prompt（游客无 sessionId，跳过历史记忆）
        AiPrompt prompt = aiPromptService.buildPrompt(message, pageContext, null);

        // 3. 调 AI
        String requestId = UUID.randomUUID().toString();
        String reply;
        AiNavigateCommand navigate;
        AiEditorCommand aiEditorCommand;
        try {
            reply = aiModelService.chat(prompt, requestId);
            navigate = getNavigateOrInfer(requestId, message);
            aiEditorCommand = getEditorActionOrInfer(requestId, message);
        } finally {
            aiToolActionRegistry.clear(requestId);
        }

        // 4. 组装临时 assistantMessage
        AiMessageVO assistantMsg = new AiMessageVO();
        assistantMsg.setId("guest-ai-" + System.currentTimeMillis());
        assistantMsg.setSessionId("guest-session");
        assistantMsg.setRole("assistant");
        assistantMsg.setContent(reply);
        assistantMsg.setPageContext(rawPageContextJson);
        assistantMsg.setCreatedAt(LocalDateTime.now());

        // 4. 组装临时 session
        AiSessionVO sessionVO = new AiSessionVO();
        sessionVO.setId("guest-session");
        sessionVO.setTitle(buildSessionTitle(message));
        sessionVO.setCreatedAt(now);
        sessionVO.setUpdatedAt(now);

        // 5. 打包返回
        AiChatVO result = new AiChatVO();
        result.setSession(sessionVO);
        result.setUserMessage(userMsg);
        result.setAssistantMessage(assistantMsg);
        result.setNavigate(navigate);
        result.setEditorAction(aiEditorCommand);
        return result;
    }


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

        // 拼完整 prompt（含历史记忆 + 页面上下文 + 当前问题）
        AiPrompt prompt = aiPromptService.buildPrompt(message, pageContext, sessionId);

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

            AiNavigateCommand navigate = getNavigateOrInfer(requestId, message);
            if (navigate != null) {
                eventData.put("navigate", navigate);
            }
            AiEditorCommand editorAction = getEditorActionOrInfer(requestId, message);
            if(editorAction != null){
                eventData.put("editorAction", editorAction);
            }

            AiArticleActionCommand articleAction = getArticleActionOrInfer(requestId, message, pageContext);
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

        // 拼 prompt（游客无 sessionId，跳过历史记忆）
        AiPrompt prompt = aiPromptService.buildPrompt(message, pageContext, null);

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
            AiNavigateCommand navigate = getNavigateOrInfer(requestId, message);

            AiMessageVO assistantMessage = new AiMessageVO();
            assistantMessage.setId("guest-ai-" + System.currentTimeMillis());
            assistantMessage.setSessionId("guest");
            assistantMessage.setRole("assistant");
            assistantMessage.setContent(reply);
            assistantMessage.setCreatedAt(LocalDateTime.now());

            Map<String, Object> eventData = new HashMap<>();
            eventData.put("session", guestSession);
            eventData.put("assistantMessage", assistantMessage);
            if (navigate != null) {
                eventData.put("navigate", navigate);
            }

            AiEditorCommand editorAction = getEditorActionOrInfer(requestId, message);
            if(editorAction != null){
                eventData.put("editorAction", editorAction);
            }

            AiArticleActionCommand articleAction = getArticleActionOrInfer(requestId, message, pageContext);
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
}
