package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.AiChatEventType;
import com.hailin.blogsystem.entity.AiMessages;
import com.hailin.blogsystem.entity.AiSessions;
import com.hailin.blogsystem.entity.Articles;
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
import com.hailin.blogsystem.service.AiSessionService;
import com.hailin.blogsystem.service.ArticlesService;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.hailin.blogsystem.service.AiModelService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
public class AiMessageServiceImpl extends ServiceImpl<AiMessageMapper, AiMessages>
        implements AiMessageService {

    private final AiSessionMapper aiSessionMapper;
    private final AiModelService aiModelService;
    private final ArticlesService articlesService;

    private final AiSessionService aiSessionService;
    private final ObjectMapper objectMapper;

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
        //promptContext 是真正喂给 AI 的上下文，里面可以包含文章标题、摘要、正文
        String promptContext = buildPromptContext(aiChatDTO.getPageContext());

        // ===== 游客模式：调 AI，不存库 =====
        if (userId == null) {  //如果 userId == null → chatAsGuest()（只拼 VO，不存库）
            return chatAsGuest(message, rawPageContextJson, promptContext);
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
        String reply = aiModelService.chat(message, promptContext);

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
    //写一个方法专门构造prompt上下文
    private String buildPromptContext(PageContextDTO pageContext){
        if (pageContext == null) {
            return "无页面上下文";
        }

        StringBuilder context = new StringBuilder();

        context.append("页面类型：").append(pageContext.getPageType()).append("\n");
        context.append("页面路径：").append(pageContext.getPath()).append("\n");

        if("article-detail".equals(pageContext.getPageType())
        && pageContext.getArticleId() != null
        && !pageContext.getArticleId().isBlank()){

            Long articleId;
            try{
                articleId = Long.valueOf(pageContext.getArticleId());

            } catch (NumberFormatException e) {
                context.append("文章ID格式错误，无法读取文章内容。\\n");

                return context.toString();
            }

            Articles article = articlesService.lambdaQuery()
                    .select(
                            Articles::getId,
                            Articles::getTitle,
                            Articles::getSummary,
                            Articles::getContent
                    )
                    .eq(Articles::getId, articleId)
                    .eq(Articles::getStatus, BlogConstants.ArticlesStatus.PUBLISHED)
                    .one();

            if (article == null) {
                context.append("当前文章不存在或未发布。\n");
                return context.toString();
            }

            context.append("\n当前文章内容：\n");
            context.append("标题：").append(article.getTitle()).append("\n");
            context.append("摘要：").append(article.getSummary()).append("\n");
            context.append("正文：\n").append(limitText(article.getContent(), 8000)).append("\n");
        }

        return context.toString();
    }
    //再加一个截断方法，防止文章太长，prompt 爆掉
    private String limitText(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "\n\n[文章内容过长，后半部分已省略]";
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
    private AiChatVO chatAsGuest(String message,String rawPageContextJson,String promptContext){
        LocalDateTime now = LocalDateTime.now();

        // 1. 组装临时 userMessage（纯 VO，不 new AiMessages，不 save）
        AiMessageVO userMsg = new AiMessageVO();
        userMsg.setId("guest-user-" + System.currentTimeMillis());
        userMsg.setSessionId("guest-session");
        userMsg.setRole("user");
        userMsg.setContent(message);
        userMsg.setPageContext(rawPageContextJson);
        userMsg.setCreatedAt(now);

        // 2. 调 AI（和登录用户完全一样）
        String reply = aiModelService.chat(message, promptContext);

        // 3. 组装临时 assistantMessage
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
        String promptContext = buildPromptContext(aiChatDTO.getPageContext());

        if(userId == null){
            return streamGuestChat(message,promptContext);
        }

        return streamUserChat(aiChatDTO,message,promptContext,rawPageContextJson,userId);
    }
    //登录用户流式逻辑
    private Flux<AiChatEventVO> streamUserChat(
            AiChatDTO aiChatDTO,
            String message,
            String promptContext,
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

        Flux<AiChatEventVO> dataEvents = aiModelService.streamChat(message, promptContext)
                .doOnNext(fullReply::append)
                .map(chunk -> AiChatEventVO.builder()
                        .eventType(AiChatEventType.DATA.getValue())
                        .eventData(chunk)
                        .build());

        Mono<AiChatEventVO> stopEvent = Mono.fromSupplier(() -> {
            AiMessages assistantMessage = saveStreamAssistantMessage(sessionId,userId,rawPageContextJson, fullReply.toString());

            AiSessions updatedSession = getOwnedSession(sessionId, userId);

            return AiChatEventVO.builder()
                    .eventType(AiChatEventType.STOP.getValue())
                    .eventData(Map.of(
                            "session", AiSessionVO.from(updatedSession),
                            "assistantMessage", AiMessageVO.from(assistantMessage)
                    ))
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
    private Flux<AiChatEventVO> streamGuestChat(String message, String promptContext) {
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

        AiChatEventVO paramEvent = AiChatEventVO.builder()
                .eventType(AiChatEventType.PARAM.getValue())
                .eventData(Map.of(
                        "session", guestSession,
                        "userMessage", userMessage
                ))
                .build();

        Flux<AiChatEventVO> dataEvents = aiModelService.streamChat(message, promptContext)
                .doOnNext(fullReply::append)
                .map(chunk -> AiChatEventVO.builder()
                        .eventType(AiChatEventType.DATA.getValue())
                        .eventData(chunk)
                        .build());

        Mono<AiChatEventVO> stopEvent = Mono.fromSupplier(() -> {
            AiMessageVO assistantMessage = new AiMessageVO();
            assistantMessage.setId("guest-ai-" + System.currentTimeMillis());
            assistantMessage.setSessionId("guest");
            assistantMessage.setRole("assistant");
            assistantMessage.setContent(fullReply.toString());
            assistantMessage.setCreatedAt(LocalDateTime.now());

            return AiChatEventVO.builder()
                    .eventType(AiChatEventType.STOP.getValue())
                    .eventData(Map.of(
                            "session", guestSession,
                            "assistantMessage", assistantMessage
                    ))
                    .build();
        });

        return Flux.concat(
                Flux.just(paramEvent),
                dataEvents,
                stopEvent
        );
    }

}
