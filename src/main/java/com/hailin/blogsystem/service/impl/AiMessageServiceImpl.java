package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.ai.billing.BillingHandle;
import com.hailin.blogsystem.ai.billing.ChatReserveCalculator;
import com.hailin.blogsystem.ai.agent.AgentFollowUpResolver;
import com.hailin.blogsystem.ai.agent.AgentRunResult;
import com.hailin.blogsystem.ai.agent.AgentRuntime;
import com.hailin.blogsystem.ai.agent.AgentStepEmitter;
import com.hailin.blogsystem.ai.agent.AgentWorkflowSuggestion;
import com.hailin.blogsystem.ai.agent.AgentWriteProposal;
import com.hailin.blogsystem.ai.agent.AgentRuntimeRouteRegistry;
import com.hailin.blogsystem.ai.agent.ArticleSessionAnchorService;
import com.hailin.blogsystem.ai.agent.LearningPlanAnchorService;
import com.hailin.blogsystem.ai.agent.ArticleAgentRuntime;
import com.hailin.blogsystem.ai.agent.GeneralAgentRuntime;
import com.hailin.blogsystem.ai.agent.LearningAgentRuntime;
import com.hailin.blogsystem.ai.planner.AgentPlannerSupport;
import com.hailin.blogsystem.ai.qa.ArticleQaTargetResolver;
import com.hailin.blogsystem.ai.qa.ArticleQaTargetResolver.QaTarget;
import com.hailin.blogsystem.ai.trace.AgentDecisionTrace;
import com.hailin.blogsystem.ai.trace.AgentDecisionTraceSink;
import com.hailin.blogsystem.ai.workflow.CreateArticleWorkflowHandler;
import com.hailin.blogsystem.ai.workflow.LearningProgressHandoffResolver;
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
import com.hailin.blogsystem.utils.MdcContext;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
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
    private final LearningAgentRuntime learningAgentRuntime;
    private final ArticleAgentRuntime articleAgentRuntime;
    private final GeneralAgentRuntime generalAgentRuntime;
    private final AgentRuntimeRouteRegistry agentRuntimeRouteRegistry;
    private final ArticleSessionAnchorService articleSessionAnchorService;
    private final LearningPlanAnchorService learningPlanAnchorService;
    private final AgentFollowUpResolver agentFollowUpResolver;
    private final LearningProgressHandoffResolver learningProgressHandoffResolver;
    private final ArticleQaTargetResolver articleQaTargetResolver;
    private final ObjectProvider<Tracer> tracerProvider;
    private final AiBillingService aiBillingService;
    private final ChatReserveCalculator chatReserveCalculator;

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

        /*
         * V4⑥ 可观测性：Flux 是惰性的——本方法体在请求线程执行（UserContext 有值可证），
         * 但返回的 Flux 由 Spring MVC 在异步线程订阅，那里没有 MDC，导致其后的
         * Agent / 工具 / 记忆召回全程丢 traceId（实测：分类器有，Agent Run 创建之后全空）。
         * 在请求线程先抓快照，订阅和自家 boundedElastic 异步边界都用这份快照显式恢复。
         * 空快照也覆盖，避免池化线程残留上一条请求的上下文。
         */
        Map<String, String> mdcSnapshot = MdcContext.capture();

        Long userId = UserContext.get();
        String rawPageContextJson = toJson(aiChatDTO.getPageContext());

        Flux<AiChatEventVO> events = userId == null
                ? streamGuestChat(message, aiChatDTO.getPageContext())
                : streamUserChat(aiChatDTO, message, aiChatDTO.getPageContext(), rawPageContextJson, userId, mdcSnapshot);

        return events.doOnSubscribe(subscription -> MdcContext.restore(mdcSnapshot));
    }
    //登录用户流式逻辑
    private Flux<AiChatEventVO> streamUserChat(
            AiChatDTO aiChatDTO,
            String message,
            PageContextDTO pageContext,
            String rawPageContextJson,
            Long userId,
            Map<String, String> mdcSnapshot
    ) {
        Long sessionId;

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
                    session,
                    mdcSnapshot
            );
        }

        // V4.x 追问续答：上一轮 Agent 在等用户回答（ASK_USER → WAITING_USER）时，这一句优先当"回答追问"
        // ——直接拿去定位计划，唯一命中就沿用原诉求继续，**不重新走分类器**
        // （分类器无状态，实测把「Java后端学习路线啊，不是Agent」判成新建计划工作流）。
        // 不满足条件返回 null，下面照常分类，行为与改动前一致。
        AgentFollowUpResolver.Resolution followUp = agentFollowUpResolver.resolve(sessionId, userId, message);

        // 分类器的消耗也是「这条消息」的成本：分类与后续回答共用一个累加器，
        // 按落点消费（普通聊天 / CTA 落 ai_messages.token_count，Agent 落 message + ai_agent_runs）。
        TokenUsageAccumulator routeUsage = new TokenUsageAccumulator();

        // 统一使用 LLM 分类结果 + Agent Planner 决策。
        // 这一轮开始，文章创作、文章优化、学习 Workflow
        // 都先经过同一个 AgentDecision。
        AiIntent intent = followUp != null
                ? followUp.intent()
                : aiIntentClassifier.classify(message, pageContext, userId, routeUsage);

        // 命中续答：Agent 的 goal 用"原诉求 + 本句回答"的合并文本。
        // **不能重新赋值 message**——下面的 Flux.defer lambda 捕获它，重新赋值会破坏 effectively final；
        // Planner / 锚写入仍按用户本句原话走，不掺合成文本（它们的判据本来就只看 intent）。
        final String agentGoal = followUp != null ? followUp.message() : message;

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

        // V4.x 会话学习计划锚：这一轮用户点名了某个计划 → 记下来，下一轮省略主语时它就是兜底依据。
        // 放在分发之前的总入口——原先写在两个 route 方法里，Agent 路径（LEARNING_AGENT）漏在外面：
        // 实测「帮我分析我的c++学习计划」走 Agent，锚没写，下一句「那就按你说的建议改」便定位不到。
        markLearningPlanAnchorIfMentioned(sessionId, userId, intent);

        // CTA 不创建 Workflow，不绑定 activeWorkflowRunId。
        if (routeDecision.getAction() == AgentAction.CTA) {
            return streamCtaMessage(
                    sessionId,
                    userId,
                    rawPageContextJson,
                    message,
                    routeDecision,
                    routeUsage
            );
        }

        // 统一处理 Workflow。
        if (routeDecision.getAction() == AgentAction.WORKFLOW) {
            /*
             * V4.x 工作流启动前的「理解展示」：先把"AI 理解成了什么"说出来，再开跑。
             * 以前是判完直接启动，用户只看到确认卡突然弹出来（实测反馈："不是说开启工作流之前会思考，
             * 还是像之前一样直接开启工作流？"）。
             *
             * 这里只能给意图级信息——真正的目标定位发生在工作流内部；用会话锚兜底时，
             * route 内部会再发一条更具体的（"按你刚才提到的《X》"），前端状态是单值，后发的覆盖这条。
             *
             * 降级 CTA / 转新建计划的分支**不发**：那些分支的实际动作与"我理解你要调整…"对不上，说了反而误导。
             */
            Flux<AiChatEventVO> understanding =
                    buildWorkflowUnderstandingStatusFlux(intent, routeDecision);

            // 文章创作 Workflow
            if (routeDecision.isWorkflow(AiWorkflowType.CREATE_ARTICLE)) {
                return Flux.concat(understanding, streamCreateArticleWorkflowFromIntent(
                        message,
                        intent,
                        pageContext,
                        rawPageContextJson,
                        userId,
                        sessionId
                ));
            }

            // 文章优化 Workflow
            if (routeDecision.isWorkflow(AiWorkflowType.OPTIMIZE_ARTICLE)) {
                return Flux.concat(understanding, streamArticleOptimizeWorkflowFromIntent(
                        message,
                        intent,
                        pageContext,
                        rawPageContextJson,
                        userId,
                        sessionId
                ));
            }

            // 学习难点攻坚 Workflow
            if (routeDecision.isWorkflow(AiWorkflowType.LEARNING_ASSIST)) {
                Optional<Flux<AiChatEventVO>> routed =
                        routeLearningAssistWorkflow(
                                message,
                                intent,
                                pageContext,
                                rawPageContextJson,
                                userId,
                                sessionId,
                                requestId
                        );

                if (routed.isPresent()) {
                    return Flux.concat(understanding, routed.get());
                }

                // 计划定位失败时降级 CTA（不发理解状态——CTA 的语义是"我还不确定"）。
                return streamCtaMessage(
                        sessionId,
                        userId,
                        rawPageContextJson,
                        message,
                        routeDecision,
                        routeUsage
                );
            }

            // 学习进度调整 Workflow
            if (routeDecision.isWorkflow(AiWorkflowType.LEARNING_PROGRESS)) {
                Optional<Flux<AiChatEventVO>> routed =
                        routeLearningProgressWorkflow(
                                message,
                                intent,
                                pageContext,
                                rawPageContextJson,
                                userId,
                                sessionId,
                                requestId
                        );

                if (routed.isPresent()) {
                    return Flux.concat(understanding, routed.get());
                }

                // 没有可调整的 ACTIVE 计划时，
                // 沿用当前行为：进入学习规划 Workflow 创建新计划。
                // 这里不发理解状态：实际动作是"新建"，与分类器判的"调整"对不上，说了反而误导。
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
                return Flux.concat(understanding, streamLearningPlanWorkflowFromIntent(
                        message,
                        intent,
                        pageContext,
                        rawPageContextJson,
                        userId,
                        sessionId,
                        requestId
                ));
            }
        }

        /*
         * Agent Runtime 分发（LEARNING_AGENT）。
         * 同步执行有界只读 Loop（maxSteps=5），结果按普通消息流式返回。
         */
        if (routeDecision.getAction() == AgentAction.AGENT) {
            // V3.5：按意图查路由注册表分发领域 Runtime（intent → runtime + 兜底文案单点登记）
            MdcContext.LogContext agentLogContext = captureAgentLogContext(mdcSnapshot);
            return dispatchAgentRuntime(
                    intent, message, agentGoal, pageContext, rawPageContextJson,
                    userId, sessionId, session, requestId, agentLogContext, routeUsage
            );
        }

        /*
         * V4.5：普通聊天 / QA 的轻量过程提示。
         *
         * 必须 concat 在慢操作之前——下面 streamNormalChatFlow 里的 QA 目标决议、
         * prompt 拼装、站内检索都是同步阻塞，若在其后才发状态，前端收到时早已结束。
         * 用 Flux.defer 让整段慢操作推迟到订阅时执行，状态事件才真正「提前」。
         */
        return Flux.concat(
                buildChatStatusFlux(routeDecision, intent),
                Flux.defer(() -> streamNormalChatFlow(
                        message, pageContext, rawPageContextJson, userId,
                        sessionId, session, routeDecision, intent, requestId, routeUsage
                ))
        );
    }

    /**
     * 分类器是否降级（调用失败 → 强制 GENERAL_CHAT）。
     *
     * 判据：{@code generalChat()} 兜底构造的 confidence 固定为 0.0——正常分类不会给 0 分。
     * 之所以要显式告诉用户：降级是**静默**的，用户看到的是"AI 突然不认我说话了"，
     * 而不是"这次是系统故障"——两者对用户的意义完全不同（前者让人怀疑产品，后者只是重试一次）。
     */
    private boolean isClassifierDegraded(AiIntent intent) {
        return intent != null
                && Double.valueOf(0.0).equals(intent.getConfidence());
    }

    /**
     * V4.5：构造首字前的过程状态事件（**只依赖 routeDecision + intent**，不依赖任何慢操作结果）。
     *
     * 文案按**意图级**给，不承诺结果：事件发出时 QaTarget 尚未决议，最终可能是
     * 追问态 / 说明态（并没真正读到文章）——所以 CURRENT_ARTICLE 只能说
     * 「正在确认文章范围」，不能写「正在读取当前文章」。
     *
     * 无检索意图（普通闲聊）返回空流：不打搅原有打字动效，不为了「全局」强行加状态。
     *
     * V4.x 补两路，都是"用户会干等、之前却没有任何提示"的场景：
     * - **学习计划查询**（dashboard 读工具）：查库 + 拼上下文 + 生成全程无提示，
     *   用户只看到干等几秒后突然冒出一大段（实测原话："我前端根本看不到他在干嘛突然就全部发出来了"）
     * - **分类器降级**：失败后静默走普通聊天，用户看到的是"AI 突然不认我的话了"而不是"系统故障"
     */
    private Flux<AiChatEventVO> buildChatStatusFlux(AgentDecision routeDecision, AiIntent intent) {
        if (routeDecision == null) {
            return Flux.empty();
        }

        String statusType;
        String retrievalMode;
        String text;
        // 判定顺序与下方实际检索分支保持一致（CURRENT_ARTICLE 优先）
        if (routeDecision.usesRetrieval("CURRENT_ARTICLE")) {
            statusType = "CURRENT_ARTICLE_RESOLVING";
            retrievalMode = "CURRENT_ARTICLE";
            text = "正在确认文章范围...";
        } else if (routeDecision.usesRetrieval("ARTICLE_SEARCH")) {
            statusType = "ARTICLE_SEARCHING";
            retrievalMode = "ARTICLE_SEARCH";
            text = "正在检索站内文章...";
        } else if (routeDecision.isTool("getLearningDashboard")) {
            statusType = "LEARNING_DASHBOARD_READING";
            retrievalMode = "NONE";
            text = "正在查看你的学习计划...";
        } else if (isClassifierDegraded(intent)) {
            statusType = "CLASSIFIER_DEGRADED";
            retrievalMode = "NONE";
            text = "这次没理解准，先按普通回答处理...";
        } else {
            return Flux.empty();
        }

        // 载荷只带展示所需字段：不带 articleId / 分数 / ES 参数 / prompt / trace
        return Flux.just(AiChatEventVO.builder()
                .eventType(AiChatEventType.CHAT_STATUS.getValue())
                .eventData(Map.of(
                        "statusType", statusType,
                        "retrievalMode", retrievalMode,
                        "message", text
                ))
                .build());
    }

    /**
     * V4.5：普通聊天 / QA 的实际流程（惰性执行——先让前端拿到过程状态）。
     *
     * 由 {@link #streamUserChat} 用 {@code Flux.defer} 包住调用；
     * 方法体是原「默认聊天 + QA」路径的全部逻辑，未做行为改动。
     */
    private Flux<AiChatEventVO> streamNormalChatFlow(
            String message,
            PageContextDTO pageContext,
            String rawPageContextJson,
            Long userId,
            Long sessionId,
            AiSessions session,
            AgentDecision routeDecision,
            AiIntent intent,
            String requestId,
            TokenUsageAccumulator routeUsage
    ) {


        // V4.5：随流构建一起搬进本方法（原在 streamUserChat 里，doFinally 的取消兜底要用它）
        AtomicBoolean assistantSaved = new AtomicBoolean(false);

        // V4.5 §4.0：首字前耗时测量——先量 resolve / buildPrompt / ragSearch / totalBeforeData，
        // 再判断前端 250ms 防抖阈值是否合理（量完可降级 debug 或移除）
        long flowStart = System.currentTimeMillis();
        long[] timings = new long[3];

        AiArticleActionCommand articleActionFromIntent = buildArticleActionFromDecision(routeDecision, intent, pageContext);
        // V3.8：页面 ACTION（点赞/收藏等明确围绕当前文章的操作）→ 会话锚写点（PAGE_ACTION）
        markAnchorForPageAction(sessionId, articleActionFromIntent, pageContext);

        /*
         * V3.9：QA 目标单点决议（每请求一次，正文注入与来源卡共用同一决议，永不分叉）。
         * 决议含可读加载（页面文章 / 会话锚 / 追问态 / 说明态），只读不写。
         */
        long resolveStart = System.currentTimeMillis();
        QaTarget qaTarget = routeDecision.usesRetrieval("CURRENT_ARTICLE")
                ? articleQaTargetResolver.resolve(message, pageContext, sessionId, userId)
                : null;
        timings[0] = System.currentTimeMillis() - resolveStart;

        String extraPromptContext = buildExtraPromptContextFromIntent(intent, pageContext);
        if ((extraPromptContext == null || extraPromptContext.isBlank())
                && qaTarget != null) {
            extraPromptContext = buildArticleDetailContextFromQaTarget(qaTarget, sessionId, pageContext);
        }

        AiNavigateCommand navigateFromIntent = buildNavigateFromDecision(routeDecision, intent, pageContext);
        AiEditorCommand editorActionFromIntent = buildEditorActionFromDecision(routeDecision, intent);

        // 拼完整 prompt（含历史记忆 + 页面上下文 + 当前问题）
        long promptStart = System.currentTimeMillis();
        AiPrompt prompt = aiPromptService.buildPrompt(message, pageContext, sessionId);
        timings[1] = System.currentTimeMillis() - promptStart;
        prompt.setArticleToolsEnabled(shouldEnableArticleTools(routeDecision));
        prompt.setSessionId(sessionId);
        prompt.setLearningDashboardToolEnabled(routeDecision.isTool("getLearningDashboard"));

        appendExtraPromptContext(prompt,extraPromptContext);  //将第一次模型回复的拼进prompt

        String actionResultPromptContext = buildActionResultPromptContext(navigateFromIntent);
        appendExtraPromptContext(prompt,actionResultPromptContext);

        boolean currentArticleRetrieval = routeDecision.usesRetrieval("CURRENT_ARTICLE");
        boolean articleSearchRetrieval = routeDecision.usesRetrieval("ARTICLE_SEARCH");

        ArticleRagContext currentArticleReference = buildCurrentArticleReferenceFromQaTarget(qaTarget);

        List<ArticleRagContext> ragContexts;

        if (currentArticleRetrieval) {
            ragContexts = currentArticleReference == null ? List.of() : List.of(currentArticleReference);
        } else if (articleSearchRetrieval) {
            long ragStart = System.currentTimeMillis();
            ArticleRagSearchResult ragSearchResult = articleRagSearchService.search(message, intent);
            timings[2] = System.currentTimeMillis() - ragStart;
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

        /*
         * AI 计费预扣（设计稿 §5.5 事务 A）。
         *
         * 位置：放在用户消息落库之后、模型调用之前。计费键必须**在调用前就存在**，
         * 而这条用户消息是此刻唯一已落库、能唯一标识「本次调用」的记录。
         *
         * 此刻 prompt 已经拼完（含 RAG / 记忆 / 会话摘要 / 页面上下文），
         * 所以预扣按**真实输入长度**算，不靠估算分布去猜（见 ChatReserveCalculator）。
         *
         * 余额不足会抛 InsufficientBalanceException——它在流构建之前抛出，
         * 由 GlobalExceptionHandler 映射成 HTTP 402，不会变成流中间的错误事件。
         */
        BillingHandle billingHandle = aiBillingService.reserve(
                ChatReserveCalculator.BIZ_TYPE,
                String.valueOf(userMessage.getId()),
                userId,
                chatReserveCalculator.estimate(prompt.getFinalPromptContext()));

        StringBuilder fullReply = new StringBuilder();

        AiChatEventVO paramEvent = AiChatEventVO.builder()
                .eventType(AiChatEventType.PARAM.getValue())
                .eventData(Map.of(
                        "session", AiSessionVO.from(session),
                        "userMessage", AiMessageVO.from(userMessage)
                ))
                .build();

        /*
         * 正文生成的用量 = **计费依据**，因此这里不复用 routeUsage（设计稿 §5.2）。
         *
         * 意图分类是平台为选路产生的固定开销，不向用户收费；而 ai_messages.token_count
         * 的口径保持不变（分类器 + 正文，供成本观测），落库时再把两者相加。
         */
        TokenUsageAccumulator replyUsage = new TokenUsageAccumulator();
        AtomicBoolean timingLogged = new AtomicBoolean(false);
        Flux<AiChatEventVO> dataEvents = aiModelService.streamChat(prompt,requestId,replyUsage)
                .doOnNext(chunk -> {
                    if (timingLogged.compareAndSet(false, true)) {
                        // §4.0 的量测目的已达成（防抖 250ms 判定合理、记忆召回串行问题已定位并修复），
                        // 降级 debug 避免每次对话刷屏；后续查「首 token 慢」时临时开 debug 即可
                        log.debug("V4.5 首字前耗时: resolve={}ms buildPrompt={}ms ragSearch={}ms totalBeforeData={}ms",
                                timings[0], timings[1], timings[2],
                                System.currentTimeMillis() - flowStart);
                    }
                    fullReply.append(chunk);
                })
                .map(chunk -> AiChatEventVO.builder()
                        .eventType(AiChatEventType.DATA.getValue())
                        .eventData(chunk)
                        .build());

        Mono<AiChatEventVO> stopEvent = Mono.fromSupplier(() -> {
            AiMessages assistantMessage = saveStreamAssistantMessage(sessionId,userId,rawPageContextJson, fullReply.toString());
            //token 用量落库（含工具调用多轮累计）。口径 = 正文生成 + 意图分类，仅供成本观测
            long messageTokens = replyUsage.getTotalTokens()
                    + (routeUsage == null ? 0 : routeUsage.getTotalTokens());
            if (messageTokens > 0) {
                assistantMessage.setTokenCount(messageTokens);
                updateById(assistantMessage);
            }

            // 结算：按**正文生成**的实际用量扣，退回预扣差额（设计稿 §5.5 事务 B）
            aiBillingService.settle(billingHandle, replyUsage);
            aiBillingService.bindResource(billingHandle, String.valueOf(assistantMessage.getId()));

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
            /*
             * 计费释放：用户取消 / 流异常时全额退回预扣。
             *
             * 正常完成走到这里时结算已经做过，release 会因 status 不再是 RESERVED 而空转，
             * 不会重复退款——结算和释放各有一道 CAS 兜住自己那一半。
             *
             * ⚠️ V1 的政策是「取消也全额退」，代价是承认一个窗口：用户可以在模型已经产出
             * token 之后取消、拿回预扣、恢复正余额，再发起下一次。这是产品取舍不是 bug，
             * 升级路径（按已产生用量部分结算）见设计稿 §3。
             */
            if (signalType == SignalType.CANCEL || signalType == SignalType.ON_ERROR) {
                aiBillingService.release(billingHandle);
            }
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

    /**
     * Agent 公共流式返回管道（V2.5 抽取，学习 / 文章域共用）。
     *
     * 同步执行领域 Runtime 的有界只读 Loop（maxSteps=5），拿到最终回答后
     * 按 32 字符切块模拟流式（与模型降级路径一致，前端无感知），
     * 消息落库与普通聊天一致。
     * AGENT_STEP / STOP 透出（workflowSuggestion / writeAction）与 agentRunId 绑定
     * 全部通用，领域差异只体现在 runtime 与兜底文案。
     */
    /**
     * Agent Runtime 分发（V3.5 收口，主分发与 fallback 分发共用）：
     * 按 intent 查路由注册表（intent → runtime + 兜底文案单点登记）。
     *
     * 未登记 intent：现在不可能发生（分类器 AGENT 白名单已全登记）——warn + 显式学习域兜底，
     * 把「新 intent 漏配静默掉进学习域」变成可见日志（漏配时去 AgentRuntimeRouteRegistry 补登记）。
     * 注意：这里不持有任何域判定逻辑（needsThinking / 游客门等在 Planner），只查表 + 防御兜底。
     */
    private Flux<AiChatEventVO> dispatchAgentRuntime(
            AiIntent intent,
            String message,
            String agentGoal,
            PageContextDTO pageContext,
            String rawPageContextJson,
            Long userId,
            Long sessionId,
            AiSessions session,
            String requestId,
            MdcContext.LogContext logContext,
            TokenUsageAccumulator routeUsage
    ) {
        String intentName = intent == null ? null : intent.getIntent();
        AgentRuntime runtime = agentRuntimeRouteRegistry.resolve(intentName);
        if (runtime == null) {
            log.warn("Agent intent 未登记路由，落入学习域显式兜底: intent={}", intentName);
            runtime = learningAgentRuntime;
        }
        String fallbackReply = agentRuntimeRouteRegistry.fallbackMessage(intentName);
        if (fallbackReply == null) {
            // 与 learning 兜底同文（防御分支：只会在未登记漏配时走到，文案随注册表 learning 行同步）
            fallbackReply = "暂时无法整理学习建议，请稍后重试。";
        }
        return streamAgentReply(
                runtime,
                message,
                agentGoal,
                pageContext,
                rawPageContextJson,
                userId,
                sessionId,
                session,
                requestId,
                fallbackReply,
                logContext,
                routeUsage
        );
    }

    private MdcContext.LogContext captureAgentLogContext(Map<String, String> fallback) {
        return MdcContext.captureWithTrace(tracerProvider.getIfAvailable(), fallback);
    }

    private Runnable wrapAgentLogContext(MdcContext.LogContext logContext, Runnable runnable) {
        return MdcContext.wrap(tracerProvider.getIfAvailable(), logContext, runnable);
    }

    private Flux<AiChatEventVO> streamAgentReply(
            AgentRuntime runtime,
            String message,        // 用户**原话**：落库 + 回传前端，必须原样（不能是续答合成文本）
            String agentGoal,      // Agent 的目标：续答时是"原诉求 + 本句回答"，其余情况等于 message
            PageContextDTO pageContext,
            String rawPageContextJson,
            Long userId,
            Long sessionId,
            AiSessions session,
            String requestId,
            String fallbackReply,
            MdcContext.LogContext logContext,
            TokenUsageAccumulator routeUsage
    ) {
        // 用户消息落库（同步，立即可见）——用原话，用户发的什么就存什么、显示什么
        AiMessages userMessage = new AiMessages();
        userMessage.setSessionId(sessionId);
        userMessage.setRole(ROLE_USER);
        userMessage.setContent(message);
        userMessage.setPageContext(rawPageContextJson);
        userMessage.setCreatedAt(LocalDateTime.now());
        save(userMessage);

        AiChatEventVO paramEvent = AiChatEventVO.builder()
                .eventType(AiChatEventType.PARAM.getValue())
                .eventData(Map.of(
                        "session", AiSessionVO.from(session),
                        "userMessage", AiMessageVO.from(userMessage)
                ))
                .build();

        /*
         * V2.3：Agent Loop 在独立线程同步执行，每步通过 AgentStepEmitter 实时推
         * AGENT_STEP 事件（前端"思考过程"面板），跑完再推正文 DATA + STOP。
         * 与 Workflow 的 Flux.create + boundedElastic 模式一致。
         */
        Flux<AiChatEventVO> agentEvents = Flux.create(sink -> {
            // V3.13：改为匿名类——lambda 不能覆写默认方法，只加接口默认方法而不改这里，
            // 计划会落库但永远不会实时展示
            AgentStepEmitter emitter = new AgentStepEmitter() {
                @Override
                public void emit(int stepNo, String actionType, String status,
                                 String stepMessage, String thoughtSummary) {
                    // 注意：不能用 Map.of——thoughtSummary 允许 null（V3.10 D3 空值可容忍），
                    // Map.of 遇 null value 直接抛 NPE 会让整个 Agent run FAILED
                    Map<String, Object> data = new HashMap<>();
                    data.put("stepNo", stepNo);
                    data.put("actionType", actionType);
                    data.put("status", status);
                    data.put("message", stepMessage);
                    // V3.10：思考摘要（清洗后，nullable；前端行文本优先于 message）
                    data.put("thoughtSummary", thoughtSummary);
                    emitIfOpen(sink, AiChatEventVO.builder()
                            .eventType(AiChatEventType.AGENT_STEP.getValue())
                            .eventData(data)
                            .build());
                }

                /**
                 * V3.13 Plan Preview：run 级计划事件。
                 *
                 * 本事件早于 STOP——那时最终消息尚未落库，前端占位消息还没有 agentRunId，
                 * 所以前端只能按**占位消息索引**归并（按 ID 匹配会把它丢掉）。
                 */
                @Override
                public void emitPlan(Long agentRunId, List<String> plan) {
                    Map<String, Object> data = new HashMap<>();
                    data.put("agentRunId", agentRunId == null ? null : String.valueOf(agentRunId));
                    data.put("plan", plan);
                    emitIfOpen(sink, AiChatEventVO.builder()
                            .eventType(AiChatEventType.AGENT_PLAN.getValue())
                            .eventData(data)
                            .build());
                }
            };

            Schedulers.boundedElastic().schedule(wrapAgentLogContext(logContext, () -> {
                UserContext.set(userId);
                try {
                    AgentRunResult result = runtime.run(
                            userId, sessionId, agentGoal, pageContext, emitter
                    );

                    String reply = result.finalAnswer() == null
                            ? fallbackReply
                            : result.finalAnswer();

                    // 正文按 32 字符切块流式输出
                    for (String chunk : splitIntoChunks(reply, 32)) {
                        emitIfOpen(sink, AiChatEventVO.builder()
                                .eventType(AiChatEventType.DATA.getValue())
                                .eventData(chunk)
                                .build());
                    }

                    // 消息落库 + 记忆提取 + 压缩 + suggestion 透出（原 STOP 逻辑）
                    AiMessages assistantMessage = saveStreamAssistantMessage(
                            sessionId, userId, rawPageContextJson, reply
                    );

                    // V2.1/V2.3：所有 Agent run 都绑定 agentRunId（建议卡 + 思考步骤刷新恢复）
                    AgentWorkflowSuggestion suggestion = result.pendingWorkflowSuggestion();
                    boolean needUpdate = false;
                    if (result.agentRunId() != null) {
                        assistantMessage.setAgentRunId(result.agentRunId());
                        needUpdate = true;
                    }
                    // token 落 message = Agent 循环消耗 + 意图分类消耗（这条消息的完整成本）。
                    // ai_agent_runs.total_tokens 只是循环内部成本，不含分类器，两者口径不同不重复
                    long messageTokens = result.totalTokens()
                            + (routeUsage == null ? 0 : routeUsage.getTotalTokens());
                    if (messageTokens > 0) {
                        assistantMessage.setTokenCount(messageTokens);
                        needUpdate = true;
                    }
                    if (needUpdate) {
                        updateById(assistantMessage);
                    }

                    aiMemoryCandidateExtractorService.extractAfterChat(
                            userId,
                            sessionId,
                            userMessage.getId(),
                            message,
                            reply
                    );
                    aiEpisodicMemoryExtractorService.extractAfterChat(
                            userId,
                            sessionId,
                            userMessage.getId(),
                            assistantMessage.getId(),
                            message,
                            reply
                    );
                    aiConversationSummaryService.compressAfterChat(userId, sessionId);

                    AiSessions updatedSession = getOwnedSession(sessionId, userId);

                    Map<String, Object> eventData = new HashMap<>();
                    eventData.put("session", AiSessionVO.from(updatedSession));
                    eventData.put("assistantMessage", AiMessageVO.from(assistantMessage));
                    if (suggestion != null) {
                        eventData.put("workflowSuggestion", suggestion);
                    }
                    // V2.4：写动作提案透出（前端渲染写动作确认卡）
                    AgentWriteProposal writeAction = result.pendingWriteAction();
                    if (writeAction != null) {
                        eventData.put("writeAction", writeAction);
                    }

                    emitIfOpen(sink, AiChatEventVO.builder()
                            .eventType(AiChatEventType.STOP.getValue())
                            .eventData(eventData)
                            .build());

                    completeIfOpen(sink);
                } catch (Throwable e) {
                    log.error("Agent 流式执行异常: userId={}", userId, e);
                    AiMessages assistantMessage = saveStreamAssistantMessage(
                            sessionId, userId, rawPageContextJson,
                            "抱歉，AI 助手暂时不可用，请稍后再试。"
                    );
                    Map<String, Object> eventData = new HashMap<>();
                    eventData.put("session", AiSessionVO.from(getOwnedSession(sessionId, userId)));
                    eventData.put("assistantMessage", AiMessageVO.from(assistantMessage));
                    emitIfOpen(sink, AiChatEventVO.builder()
                            .eventType(AiChatEventType.STOP.getValue())
                            .eventData(eventData)
                            .build());
                    completeIfOpen(sink);
                } finally {
                    UserContext.clear();
                }
            }));
        });

        return Flux.concat(
                Flux.just(paramEvent),
                agentEvents
        ).doFinally(signalType -> aiToolActionRegistry.clear(requestId));
    }

    private List<String> splitIntoChunks(String text, int chunkSize) {
        if (text == null || text.isEmpty()) {
            return List.of("");
        }
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < text.length(); i += chunkSize) {
            chunks.add(text.substring(i, Math.min(text.length(), i + chunkSize)));
        }
        return chunks;
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
        // V4⑥ 可观测性：Flux 惰性——boundedElastic 的提交发生在订阅线程，那里没有 MDC。
        // 在请求线程先抓日志上下文，执行线程恢复（与 streamAgentReply 同模式）。
        MdcContext.LogContext workflowLogContext = captureAgentLogContext(MdcContext.capture());

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
                Schedulers.boundedElastic().schedule(wrapAgentLogContext(workflowLogContext, () -> {
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
                                emitIfOpen(sink, AiChatEventVO.builder()
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
                                    emitIfOpen(sink, AiChatEventVO.builder()
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

                        emitIfOpen(sink, AiChatEventVO.builder()
                                .eventType(AiChatEventType.STOP.getValue())
                                .eventData(eventData)
                                .build());

                        completeIfOpen(sink);
                    } catch (Throwable e) {
                        emitRejectedOrError(sink, sessionId, userId, rawPageContextJson,
                                AiWorkflowType.OPTIMIZE_ARTICLE.name(), e);
                    } finally {
                        UserContext.clear();
                    }
                }))
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
            if (!sink.isCancelled()) {
                sink.error(e);
            }
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

        emitIfOpen(sink, AiChatEventVO.builder()
                .eventType(AiChatEventType.STOP.getValue())
                .eventData(eventData)
                .build());

        completeIfOpen(sink);
    }

    private Optional<Flux<AiChatEventVO>> routeLearningAssistWorkflow(
            String message,
            AiIntent intent,
            PageContextDTO pageContext,
            String rawPageContextJson,
            Long userId,
            Long sessionId,
            String requestId
    ) {
        // 分类器在理解阶段就从「该用户真实计划列表」里选定了目标（learningPlanId 为后端映射所得，
        // 越界已在分类器内丢弃）。命中即直达，不再做字符串匹配——这是「说了 c++ 却被反问选哪个」的修复点。
        LearningPlans authoritative = loadAuthoritativePlan(userId, intent);
        if (authoritative != null) {
            return Optional.of(streamLearningAssistWorkflowFromIntent(
                    message,
                    authoritative,
                    List.of(),
                    pageContext,
                    rawPageContextJson,
                    userId,
                    sessionId,
                    requestId
            ));
        }

        List<LearningPlans> mentioned = resolveMentionedActivePlans(userId, learningPlanRefOf(intent));
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

        // V4.x 会话学习计划锚兜底（同 routeLearningProgressWorkflow，位置与理由一致）
        LearningPlans anchored = learningPlanAnchorService.resolve(sessionId, userId);
        if (anchored != null) {
            return Optional.of(Flux.concat(
                    buildAnchorUsedStatusFlux(anchored),
                    streamLearningAssistWorkflowFromIntent(
                            message,
                            anchored,
                            List.of(),
                            pageContext,
                            rawPageContextJson,
                            userId,
                            sessionId,
                            requestId
                    )
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
            AiIntent intent,
            PageContextDTO pageContext,
            String rawPageContextJson,
            Long userId,
            Long sessionId,
            String requestId
    ) {
        // 同 routeLearningAssistWorkflow：分类器选定的权威计划优先，命中即直达，不再做字符串匹配
        LearningPlans authoritative = loadAuthoritativePlan(userId, intent);
        if (authoritative != null) {
            return Optional.of(streamLearningProgressWorkflowFromIntent(
                    message,
                    authoritative,
                    List.of(),
                    pageContext,
                    rawPageContextJson,
                    userId,
                    sessionId,
                    requestId
            ));
        }

        List<LearningPlans> mentioned = resolveMentionedActivePlans(userId, learningPlanRefOf(intent));
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

        /*
         * V4.x 会话学习计划锚兜底：这一句里没有计划名（多轮对话省略主语，如
         * 「我是要应对学校课程，你会怎么改」）时，用本会话最近一次**真实定位到**的计划。
         *
         * 位置：在"原话匹配"之后、"只剩一个 ACTIVE 计划"之前——
         * 「用户这次说了什么」永远优先于「上次聊的是哪个」，而锚比"只剩一个计划"的隐含推断更可靠。
         *
         * 用了锚**必须报出来**（见 buildAnchorUsedStatusFlux）：那是合理的猜测而非确定，
         * 猜错了用户得有机会纠正——沉默地改错计划是最坏结果。
         */
        LearningPlans anchored = learningPlanAnchorService.resolve(sessionId, userId);
        if (anchored != null) {
            return Optional.of(Flux.concat(
                    buildAnchorUsedStatusFlux(anchored),
                    streamLearningProgressWorkflowFromIntent(
                            message,
                            anchored,
                            List.of(),
                            pageContext,
                            rawPageContextJson,
                            userId,
                            sessionId,
                            requestId
                    )
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

    /**
     * V4.x：用了会话学习计划锚兜底时的过程状态。
     *
     * 「按你刚才提到的《X》来处理」——作用是让**猜测可见**：锚是"上一轮聊过"的推断，
     * 不是用户这一句说的。报了用户才知道系统理解成了哪个，猜错能当场纠正。
     */
    private Flux<AiChatEventVO> buildAnchorUsedStatusFlux(LearningPlans plan) {
        if (plan == null || plan.getTitle() == null || plan.getTitle().isBlank()) {
            return Flux.empty();
        }
        return Flux.just(AiChatEventVO.builder()
                .eventType(AiChatEventType.CHAT_STATUS.getValue())
                .eventData(Map.of(
                        "statusType", "SESSION_PLAN_ANCHOR_USED",
                        "retrievalMode", "NONE",
                        "message", "按你刚才提到的《" + plan.getTitle() + "》来处理..."
                ))
                .build());
    }

    /**
     * V4.x：工作流启动前的「理解展示」。
     *
     * 工作流以前是"判完直接开跑"——用户只看到确认卡突然弹出来，中间没有"它理解成了什么"这一步
     * （实测反馈："不是说开启工作流之前会思考，还是像之前一样直接开启工作流？"）。
     *
     * 文案只能用**意图级信息 + 用户原话里的计划名**：真正的目标定位发生在工作流内部，这里还拿不到。
     * 用会话锚兜底时 route 内部会再发一条更具体的（"按你刚才提到的《X》"），
     * 前端状态是单值、后发的覆盖先发的，所以两条连着发不会打架。
     */
    private Flux<AiChatEventVO> buildWorkflowUnderstandingStatusFlux(AiIntent intent, AgentDecision decision) {
        if (decision == null || decision.getWorkflowType() == null) {
            return Flux.empty();
        }
        String planRef = learningPlanRefOf(intent);
        String planPart = planRef == null || planRef.isBlank() ? "" : "《" + planRef + "》";

        String text = switch (decision.getWorkflowType()) {
            case LEARNING_PROGRESS -> "我理解你要调整学习计划" + planPart + "，正在准备...";
            case LEARNING_ASSIST -> "我理解你要拆解学习难点" + planPart + "，正在准备...";
            case LEARNING_PLAN -> "我理解你想制定学习计划，正在准备...";
            case CREATE_ARTICLE -> "我理解你想写一篇新文章，正在准备...";
            case OPTIMIZE_ARTICLE -> "我理解你想优化当前文章，正在准备...";
        };

        return Flux.just(AiChatEventVO.builder()
                .eventType(AiChatEventType.CHAT_STATUS.getValue())
                .eventData(Map.of(
                        "statusType", "WORKFLOW_UNDERSTANDING",
                        "retrievalMode", "NONE",
                        "message", text
                ))
                .build());
    }

    /**
     * V4.x 会话学习计划锚的**唯一写点**：用户这一轮点名了某个计划，且能唯一定位到 → 记下来。
     *
     * 为什么放在总入口而不是各个 route 方法里：路由出口有多条（工作流 / Agent / 聊天），
     * 只在工作流两处写会漏掉 Agent——实测「帮我分析我的c++学习计划」走 Agent 路径，
     * 锚没写，下一句「那就按你说的建议改」就定位不到（会话不同也会不命中，那是锚固有的会话边界）。
     *
     * 只对「操作已有计划」的意图写：新建计划（LEARNING_PLAN）语境里出现的计划名，
     * 多半是"要创建的那个"而不是"已有的某个"，写进锚会误导下一轮。
     *
     * 定位优先用分类器的权威 ID，否则按原话匹配——**不唯一就不写**（宁可不猜）。
     */
    private void markLearningPlanAnchorIfMentioned(Long sessionId, Long userId, AiIntent intent) {
        if (sessionId == null || userId == null || intent == null) {
            return;
        }
        String intentName = intent.getIntent();
        if (!"LEARNING_PROGRESS".equals(intentName)
                && !"LEARNING_ASSIST".equals(intentName)
                && !"LEARNING_AGENT".equals(intentName)) {
            return;
        }
        String planRef = learningPlanRefOf(intent);
        if (planRef == null || planRef.isBlank()) {
            // 这一轮没点名计划——没有新信息，不动锚（保留会话里已有的那个）
            return;
        }

        LearningPlans plan = loadAuthoritativePlan(userId, intent);
        String source = LearningPlanAnchorService.SOURCE_CLASSIFIER;
        if (plan == null) {
            List<LearningPlans> matched = resolveMentionedActivePlans(userId, planRef);
            if (matched.size() != 1) {
                return;
            }
            plan = matched.get(0);
            source = LearningPlanAnchorService.SOURCE_BACKEND_MATCH;
        }
        learningPlanAnchorService.mark(sessionId, plan.getId(), source);
    }

    /**
     * 分类器选定的权威计划（V4.x）：模型从注入的真实列表里选序号，后端映射成 ID，越界已在上游丢弃。
     * 这里再校验一次存在 + ACTIVE + 归属（防御纵深：ID 虽由后端写入，仍不无条件信任）。
     * 任一不满足返回 null，由调用方落回关键词匹配兜底。
     */
    private LearningPlans loadAuthoritativePlan(Long userId, AiIntent intent) {
        if (intent == null || intent.getLearningPlanId() == null) {
            return null;
        }
        try {
            LearningPlans plan = learningPlansService.getById(Long.valueOf(intent.getLearningPlanId()));
            if (plan == null || !userId.equals(plan.getUserId())
                    || !LearningPlans.STATUS_ACTIVE.equals(plan.getStatus())) {
                return null;
            }
            return plan;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    //分类器摘录的计划名——仅作关键词匹配的输入（权威 ID 缺失时的兜底路径）
    private String learningPlanRefOf(AiIntent intent) {
        return intent == null ? null : intent.getLearningPlanRef();
    }

    /**
     * 按「用户点名的计划」匹配——**只认分类器摘录的 planRef**。
     *
     * V4.x 修正：原先 planRef 为空时退回用**整句 message** 去匹配，后果是：
     * 「行，那就按照你说的优化建议，帮我优化一下这个计划」靠"优化""计划"两个通用词
     * 命中了《Redis 核心原理与工程化实战计划（结构优化版）》（标题含"优化"），
     * 而 C++ 计划标题只含"计划"——Redis 得分更高成为唯一最高分，被当成**点名成功**，
     * 于是锚兜底整条路被跳过，用户以为是 C++ 却改了 Redis（实测踩中）。
     *
     * 判据：**分类器摘出 planRef = 用户点名了计划**——它判"用户提到哪个计划"比字符串匹配可靠得多。
     * planRef 为空就是**没点名**：交给会话锚去推断，而不是在这里用整句话碰运气。
     */
    private List<LearningPlans> resolveMentionedActivePlans(Long userId, String learningPlanRef) {
        if (learningPlanRef == null || learningPlanRef.isBlank()) {
            return List.of();
        }
        return learningPlansService.matchPlansByMessage(userId, learningPlanRef);
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

        // V4⑥ 可观测性：boundedElastic 的提交发生在订阅线程（无 MDC），
        // 在请求线程先抓日志上下文，执行线程恢复（与 streamAgentReply 同模式）
        MdcContext.LogContext workflowLogContext = captureAgentLogContext(MdcContext.capture());
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
                Schedulers.boundedElastic().schedule(wrapAgentLogContext(workflowLogContext, () -> {
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
                                emitIfOpen(sink, AiChatEventVO.builder()
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
                                    emitIfOpen(sink, AiChatEventVO.builder()
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

                        emitIfOpen(sink, AiChatEventVO.builder()
                                .eventType(AiChatEventType.STOP.getValue())
                                .eventData(eventData)
                                .build());

                        completeIfOpen(sink);
                    } catch (Throwable e) {
                        emitRejectedOrError(sink, sessionId, userId, rawPageContextJson,
                                AiWorkflowType.CREATE_ARTICLE.name(), e);
                    } finally {
                        UserContext.clear();
                    }
                }))
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

        // V4⑥ 可观测性：boundedElastic 的提交发生在订阅线程（无 MDC），
        // 在请求线程先抓日志上下文，执行线程恢复（与 streamAgentReply 同模式）
        MdcContext.LogContext workflowLogContext = captureAgentLogContext(MdcContext.capture());
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
                Schedulers.boundedElastic().schedule(wrapAgentLogContext(workflowLogContext, () -> {
                    UserContext.set(userId);
                    try {
                        AiWorkflowStepEmitter emitter = buildLearningWorkflowEmitter(AiWorkflowType.LEARNING_PLAN, sink);

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

                        emitIfOpen(sink, AiChatEventVO.builder()
                                .eventType(AiChatEventType.STOP.getValue())
                                .eventData(eventData)
                                .build());

                        completeIfOpen(sink);
                    } catch (Throwable e) {
                        emitRejectedOrError(sink, sessionId, userId, rawPageContextJson,
                                AiWorkflowType.LEARNING_PLAN.name(), e);
                    } finally {
                        UserContext.clear();
                    }
                }))
        );

        return Flux.concat(Flux.just(paramEvent), workflowEvents);
    }

    /**
     * 学习类 Workflow 初始链路步骤事件（V3.1 修复：原为空实现——初始链路 30s~2min 期间前端
     * 只转圈无卡片，确认卡最后才整体弹出。与文章类 emitter 对齐：实时推 WORKFLOW_STEP，
     * 前端 applyInitialWorkflowStepCard 会用步骤事件先建「执行中」临时卡）。
     * runId 由 create 落库后 bind（AiWorkflowRunServiceImpl 统一处理）。
     */
    private AiWorkflowStepEmitter buildLearningWorkflowEmitter(
            AiWorkflowType workflowType,
            reactor.core.publisher.FluxSink<AiChatEventVO> sink
    ) {
        AtomicReference<Long> workflowRunId = new AtomicReference<>();
        return new AiWorkflowStepEmitter() {
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
                eventData.put("workflowType", workflowType.name());
                eventData.put("step", step);
                eventData.put("status", status);
                eventData.put("message", stepMessage);
                emitIfOpen(sink, AiChatEventVO.builder()
                        .eventType(AiChatEventType.WORKFLOW_STEP.getValue())
                        .eventData(eventData)
                        .build());
            }

            @Override
            public void emitContent(String step, String field, String delta) {
                // 学习计划 JSON 不流式推前端，确认面板从 workflow 数据渲染
            }
        };
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

        // V4⑥ 可观测性：boundedElastic 的提交发生在订阅线程（无 MDC），
        // 在请求线程先抓日志上下文，执行线程恢复（与 streamAgentReply 同模式）
        MdcContext.LogContext workflowLogContext = captureAgentLogContext(MdcContext.capture());
        AiWorkflowLearningProgressDTO workflowDTO = new AiWorkflowLearningProgressDTO();
        workflowDTO.setConversationId(sessionId);
        workflowDTO.setPlanId(targetPlan == null ? null : targetPlan.getId());
        workflowDTO.setCandidates(candidates == null || candidates.isEmpty() ? null : candidates);
        workflowDTO.setRequest(message);
        if (targetPlan != null) {
            learningProgressHandoffResolver.resolve(sessionId, userId, message)
                    .ifPresent(handoff -> {
                        workflowDTO.setHandoffReason(handoff.suggestedDirection());
                        workflowDTO.setHandoffSourceAgentRunId(handoff.sourceAgentRunId());
                    });
        }

        AiChatEventVO paramEvent = AiChatEventVO.builder()
                .eventType(AiChatEventType.PARAM.getValue())
                .eventData(Map.of(
                        "session", AiSessionVO.from(getOwnedSession(sessionId, userId)),
                        "userMessage", AiMessageVO.from(userMessage)
                ))
                .build();

        Flux<AiChatEventVO> workflowEvents = Flux.create(sink ->
                Schedulers.boundedElastic().schedule(wrapAgentLogContext(workflowLogContext, () -> {
                    UserContext.set(userId);
                    try {
                        AiWorkflowStepEmitter emitter = buildLearningWorkflowEmitter(AiWorkflowType.LEARNING_PROGRESS, sink);

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

                        emitIfOpen(sink, AiChatEventVO.builder()
                                .eventType(AiChatEventType.STOP.getValue())
                                .eventData(eventData)
                                .build());

                        completeIfOpen(sink);
                    } catch (Throwable e) {
                        emitRejectedOrError(sink, sessionId, userId, rawPageContextJson,
                                AiWorkflowType.LEARNING_PROGRESS.name(), e);
                    } finally {
                        UserContext.clear();
                    }
                }))
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

        // V4⑥ 可观测性：boundedElastic 的提交发生在订阅线程（无 MDC），
        // 在请求线程先抓日志上下文，执行线程恢复（与 streamAgentReply 同模式）
        MdcContext.LogContext workflowLogContext = captureAgentLogContext(MdcContext.capture());
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
                Schedulers.boundedElastic().schedule(wrapAgentLogContext(workflowLogContext, () -> {
                    UserContext.set(userId);
                    try {
                        AiWorkflowStepEmitter emitter = buildLearningWorkflowEmitter(AiWorkflowType.LEARNING_ASSIST, sink);

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

                        emitIfOpen(sink, AiChatEventVO.builder()
                                .eventType(AiChatEventType.STOP.getValue())
                                .eventData(eventData)
                                .build());

                        completeIfOpen(sink);
                    } catch (Throwable e) {
                        emitRejectedOrError(sink, sessionId, userId, rawPageContextJson,
                                AiWorkflowType.LEARNING_ASSIST.name(), e);
                    } finally {
                        UserContext.clear();
                    }
                }))
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
            AiSessions session,
            Map<String, String> mdcSnapshot
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
                    message, pageContext, rawPageContextJson, userId, sessionId, userMessage, mdcSnapshot);
        }

        if (workflow == null) {
            clearSessionActiveWorkflow(session);
            return fallbackToNormalChatAfterWorkflowGone(
                    message, pageContext, rawPageContextJson, userId, sessionId, userMessage, mdcSnapshot);
        }

        AiWorkflowStatus status;
        try {
            status = AiWorkflowStatus.valueOf(workflow.getStatus());
        } catch (Exception e) {
            clearSessionActiveWorkflow(session);
            return fallbackToNormalChatAfterWorkflowGone(
                    message, pageContext, rawPageContextJson, userId, sessionId, userMessage, mdcSnapshot);
        }

        // 已结束的状态 → 清空绑定，回普通聊天
        if (status == AiWorkflowStatus.COMPLETED
                || status == AiWorkflowStatus.CANCELLED
                || status == AiWorkflowStatus.FAILED) {
            clearSessionActiveWorkflow(session);
            return fallbackToNormalChatAfterWorkflowGone(
                    message, pageContext, rawPageContextJson, userId, sessionId, userMessage, mdcSnapshot);
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
            AiMessages userMessage,
            Map<String, String> mdcSnapshot
    ) {
        AiSessions session = getOwnedSession(sessionId, userId);

        /*
         * 回退路径也必须使用新的 requestId。
         * 这样分类、Planner 和后续模型调用仍然可以串成一条链。
         */
        String requestId = UUID.randomUUID().toString();

        TokenUsageAccumulator routeUsage = new TokenUsageAccumulator();
        AiIntent intent = aiIntentClassifier.classify(
                message,
                pageContext,
                userId,
                routeUsage
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
                    routeDecision,
                    routeUsage
            );
        }

        /*
         * Agent Runtime 与 Workflow 一致：
         * 删除回退路径已保存的 userMessage，
         * 由 streamAgentReply 内部重新保存。
         */
        if (routeDecision.getAction() == AgentAction.AGENT) {
            removeById(userMessage.getId());

            // V3.5：与主分发同一注册表分发（intent → runtime + 兜底文案单点）
            // 这条是 Workflow 回退路径，不经过追问续答 → 目标就是用户原话（两个参数相同）
            return dispatchAgentRuntime(
                    intent, message, message, pageContext, rawPageContextJson,
                    userId, sessionId, session, requestId, captureAgentLogContext(mdcSnapshot),
                    routeUsage
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
                                intent,
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
                        routeDecision,
                        routeUsage
                );
            }

            // 学习进度调整
            if (routeDecision.isWorkflow(AiWorkflowType.LEARNING_PROGRESS)) {
                Optional<Flux<AiChatEventVO>> routed =
                        routeLearningProgressWorkflow(
                                message,
                                intent,
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
        //
        // V4.5：过程状态必须先于慢操作 concat——回退路径与主入口共用同一 helper，
        // 否则「回退到普通聊天」这条出口依然是无语义等待。
        return Flux.concat(
                buildChatStatusFlux(routeDecision, intent),
                Flux.defer(() -> streamFallbackChatFlow(
                        message, pageContext, rawPageContextJson, userId, sessionId,
                        routeDecision, intent, requestId, userMessage, routeUsage
                ))
        );
    }

    /**
     * V4.5：Workflow 回退后的普通聊天 / QA 流程（惰性执行——先让前端拿到过程状态）。
     *
     * 方法体是原回退路径的普通聊天逻辑，未做行为改动。
     */
    private Flux<AiChatEventVO> streamFallbackChatFlow(
            String message,
            PageContextDTO pageContext,
            String rawPageContextJson,
            Long userId,
            Long sessionId,
            AgentDecision routeDecision,
            AiIntent intent,
            String requestId,
            AiMessages userMessage,
            TokenUsageAccumulator routeUsage
    ) {
        AiPrompt prompt = aiPromptService.buildPrompt(message, pageContext, sessionId);
        prompt.setArticleToolsEnabled(shouldEnableArticleTools(routeDecision));
        prompt.setSessionId(sessionId);
        prompt.setLearningDashboardToolEnabled(
                routeDecision.isTool("getLearningDashboard")
        );

        // V3.9：QA 目标单点决议（与主分发同款——正文注入与来源卡共用同一决议）
        QaTarget qaTarget = routeDecision.usesRetrieval("CURRENT_ARTICLE")
                ? articleQaTargetResolver.resolve(message, pageContext, sessionId, userId)
                : null;

        List<ArticleRagContext> ragContexts;

        if (routeDecision.usesRetrieval("CURRENT_ARTICLE")) {
            ArticleRagContext currentArticleReference =
                    buildCurrentArticleReferenceFromQaTarget(qaTarget);

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
                && qaTarget != null) {
            extraPromptContext =
                    buildArticleDetailContextFromQaTarget(qaTarget, sessionId, pageContext);
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

        // 复用调用方传来的累加器，让意图分类的消耗也算进这条消息；没传就自己开一个
        TokenUsageAccumulator usage = routeUsage == null ? new TokenUsageAccumulator() : routeUsage;
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
            AgentDecision decision,
            TokenUsageAccumulator routeUsage
    ) {
        String content = buildPlannerCtaContent(decision);

        AiMessages assistantMessage = saveStreamAssistantMessage(
                sessionId,
                userId,
                rawPageContextJson,
                content
        );

        // CTA 正文由规则生成、不调 LLM，但意图分类是真花钱的——把那份消耗记在这条消息上
        if (routeUsage != null && routeUsage.getTotalTokens() > 0) {
            assistantMessage.setTokenCount((long) routeUsage.getTotalTokens());
            updateById(assistantMessage);
        }

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
            AgentDecision decision,
            TokenUsageAccumulator routeUsage
    ) {
        AiMessages userMessage = new AiMessages();
        userMessage.setSessionId(sessionId);
        userMessage.setRole(ROLE_USER);
        userMessage.setContent(userMessageText);
        userMessage.setPageContext(rawPageContextJson);
        userMessage.setCreatedAt(LocalDateTime.now());
        save(userMessage);

        String content = buildPlannerCtaContent(decision);

        AiMessages assistantMessage = saveStreamAssistantMessage(
                sessionId,
                userId,
                rawPageContextJson,
                content
        );

        // CTA 正文由规则生成、不调 LLM，但意图分类是真花钱的——把那份消耗记在这条消息上
        if (routeUsage != null && routeUsage.getTotalTokens() > 0) {
            assistantMessage.setTokenCount((long) routeUsage.getTotalTokens());
            updateById(assistantMessage);
        }

        return buildSimpleStopFlux(sessionId, userId, userMessage, assistantMessage, null);
    }

    /**
     * CTA 文案（V4.x 重写）。
     *
     * 旧版把 decision.reason（开发者诊断串）直接拼进用户可见文案——「分类器置信度不足，降级 CTA」
     * 「LLM 建议启动 Workflow，但后端规则未命中」这类内部术语会被用户读到，而且用户无法据此行动；
     * 副作用是改 reason 措辞会静默改掉用户看到的字。
     * 现在按 decision.ctaKind 分类说话：用户能行动的给具体指引，用户做不了的原因
     * （系统自身不确定 / 配额）只给中性引导，不解释内部机制。
     * 诊断信息仍在 reason 与 AgentDecisionTrace 中，不进用户文案。
     *
     * CTA 只保存普通消息：
     * - 不创建 Workflow Run
     * - 不绑定 activeWorkflowRunId
     */
    private String buildPlannerCtaContent(AgentDecision decision) {
        CtaKind kind = decision == null || decision.getCtaKind() == null
                ? CtaKind.SYSTEM_UNCERTAIN
                : decision.getCtaKind();

        return switch (kind) {
            case MISSING_ARTICLE_CONTEXT -> "你说的这篇文章我这边还没有出现过。\n\n"
                    + "打开那篇文章的详情页，再对我说\"把这篇…\"，我就能直接帮你处理。";
            case PAGE_CONTEXT_MISMATCH -> "这个操作要在对应的页面上做才行。\n\n"
                    + "先打开那篇文章的详情页（或文章编辑器），再跟我说一次。";
            case AMBIGUOUS_REQUEST -> "我还不太确定你想做什么，能再说一句吗？\n\n"
                    + "比如告诉我是想调整学习计划、写一篇新文章，还是优化已有的文章。";
            case QUOTA_REACHED -> "这个会话里我已经自动帮你启动过几次流程了。\n\n"
                    + "这次请你明确说一句要做什么，我再开始。";
            case SYSTEM_UNCERTAIN -> systemUncertainCtaContent(decision);
        };
    }

    /**
     * 「系统不确定」的 CTA 文案：按 intent 给一句确认式引导。
     *
     * 置信度不足 / 风险偏高 / 分类器字段不一致这些都是系统内部状态——用户既看不懂也无从改进，
     * 所以不说这些，改说"我猜你是想做 X，对吗"，用户用一句话就能确认或纠正。
     */
    private String systemUncertainCtaContent(AgentDecision decision) {
        String intent = decision == null ? null : decision.getIntent();

        if ("CREATE_ARTICLE_WORKFLOW".equals(intent)) {
            return "你是想让我帮你写文章吗？告诉我主题就行，例如：Redis 缓存、Kafka 消息队列。";
        }
        if ("OPTIMIZE_ARTICLE_WORKFLOW".equals(intent)) {
            return "你是想优化某篇文章吗？打开那篇文章的详情页，再跟我说一次就行。";
        }
        if ("ARTICLE_DETAIL_QA".equals(intent)) {
            return "你是想问某篇文章的问题吗？打开那篇文章再问我。";
        }
        if ("LEARNING_PLAN".equals(intent)
                || "LEARNING_PROGRESS".equals(intent)
                || "LEARNING_ASSIST".equals(intent)) {
            return "你是想调整学习计划吗？告诉我是哪个计划、想怎么改，我来帮你处理。";
        }
        return "我还不太确定你想让我做什么。\n\n"
                + "你可以直接说具体一点，比如想调整学习计划、写新文章，或者优化已有的文章。";
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

        if ("LEARNING_AGENT".equals(currentIntent)) {
            return "学习建议助手需要登录后使用，请先登录后再试。";
        }

        if ("ARTICLE_AGENT".equals(currentIntent)) {
            return "文章优化助手需要登录后使用，请先登录后再试。";
        }

        return "这个功能需要登录后使用，请先登录后再试。";
    }

    /**
     * 分类器是否已判出明确的业务 Workflow 诉求（V3.0）。
     *
     * 游客路径用：即使 Planner 双签未命中降级 CTA，明确诉求也先给登录提示，
     * 而不是让游客反复补充信息。主题未明确的文章创作仍保留追问（见 buildGuestDecisionContent）。
     */
    private boolean isExplicitGuestBusinessIntent(AiIntent intent) {
        if (intent == null) {
            return false;
        }
        return "WORKFLOW".equals(intent.getSuggestedAction())
                && intent.getSuggestedWorkflowType() != null
                && !intent.getSuggestedWorkflowType().isBlank();
    }

    /**
     * 游客 CTA 文案（V4.x：与登录路径同源，不再拼 reason）。
     *
     * 游客没有持久化 session，所以这里只返回一次性提示，不保存消息。
     * 游客的 CTA 绝大多数是「这个操作要登录」（游客进不了 Workflow / Tool），
     * 只有分类器主动建议澄清时才需要追问一句。
     */
    private String buildGuestCtaContent(
            AgentDecision decision
    ) {
        if (decision != null && decision.getCtaKind() == CtaKind.AMBIGUOUS_REQUEST) {
            return "我还不太确定你想做什么，能再说一句吗？";
        }

        return "这个操作需要登录后才能使用。\n\n"
                + "登录后我就能帮你制定学习计划、写文章和优化文章了。";
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

    private void emitIfOpen(FluxSink<AiChatEventVO> sink, AiChatEventVO event) {
        if (!sink.isCancelled()) {
            sink.next(event);
        }
    }

    private void completeIfOpen(FluxSink<AiChatEventVO> sink) {
        if (!sink.isCancelled()) {
            sink.complete();
        }
    }

    /** 清空 session 的 activeWorkflowRunId */
    private void clearSessionActiveWorkflow(AiSessions session) {
        if (session.getActiveWorkflowRunId() == null) {
            return;
        }
        Long activeWorkflowRunId = session.getActiveWorkflowRunId();
        aiSessionService.lambdaUpdate()
                .eq(AiSessions::getId, session.getId())
                .eq(AiSessions::getUserId, session.getUserId())
                .eq(AiSessions::getActiveWorkflowRunId, activeWorkflowRunId)
                .set(AiSessions::getActiveWorkflowRunId, null)
                .set(AiSessions::getUpdatedAt, LocalDateTime.now())
                .update();
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
        // userId 传 null：游客没有学习计划列表，分类器不注入、learningPlanIndex 无意义
        TokenUsageAccumulator routeUsage = new TokenUsageAccumulator();
        AiIntent intent = aiIntentClassifier.classify(
                message,
                pageContext,
                null,
                routeUsage
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
         * 游客不能执行 Workflow / Agent Runtime。
         *
         * 文章创作、文章优化、学习规划、学习调整、难点攻坚、学习建议 Agent，
         * 都统一返回登录提示。
         * V3.0：分类器已判出明确业务诉求（WORKFLOW 建议）但 Planner 因双签规则
         * 未命中降级 CTA 时，也走登录提示——游客不该被"信息不足"追问（登录才是硬边界）。
         */
        if (guestDecision.getAction() == AgentAction.WORKFLOW
                || guestDecision.getAction() == AgentAction.TOOL
                || guestDecision.getAction() == AgentAction.AGENT
                || isExplicitGuestBusinessIntent(intent)) {

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

        // V4.5：游客路径同样先发过程状态——三处出口共用同一 helper，避免「游客无提示」
        return Flux.concat(
                buildChatStatusFlux(guestDecision, intent),
                Flux.defer(() -> streamGuestChatFlow(
                        message, pageContext, intent, guestDecision,
                        fullReply, now, guestSession, userMessage, requestId
                ))
        );
    }

    /**
     * V4.5：游客普通聊天 / QA 流程（惰性执行——先让前端拿到过程状态）。
     *
     * 游客不落库，过程状态同样是瞬态的（不持久化）。
     * 方法体是原游客普通聊天逻辑，未做行为改动。
     */
    private Flux<AiChatEventVO> streamGuestChatFlow(
            String message,
            PageContextDTO pageContext,
            AiIntent intent,
            AgentDecision guestDecision,
            StringBuilder fullReply,
            String now,
            AiSessionVO guestSession,
            AiMessageVO userMessage,
            String requestId
    ) {
        AiArticleActionCommand articleActionFromIntent = buildArticleActionFromDecision(guestDecision, intent, pageContext);

        // V3.9：游客无归属会话（sessionId/userId null）→ 无会话锚，QA 决议只剩页面候选或说明态
        QaTarget qaTarget = guestDecision.usesRetrieval("CURRENT_ARTICLE")
                ? articleQaTargetResolver.resolve(message, pageContext, null, null)
                : null;
        String extraPromptContext = buildExtraPromptContextFromIntent(intent, pageContext);
        if ((extraPromptContext == null || extraPromptContext.isBlank())
                && qaTarget != null) {
            // 游客流 sessionId 为 null（会话锚写点跳过）
            extraPromptContext = buildArticleDetailContextFromQaTarget(qaTarget, null, pageContext);
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

        ArticleRagContext currentArticleReference = buildCurrentArticleReferenceFromQaTarget(qaTarget);

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


    /**
     * V3.8：页面 ACTION（点赞/收藏/关注等围绕当前文章的明确操作）→ 会话锚写点（PAGE_ACTION）。
     * 仅在已解析出页面动作且 pageContext 带当前文章 ID 时写；ID 非法/无动作不写。
     * 不校验文章存在（mark 只记上下文，resolve 读取时查库判有效性——锚不会指到失效文章）。
     */
    private void markAnchorForPageAction(Long sessionId, AiArticleActionCommand action, PageContextDTO pageContext) {
        if (action == null || sessionId == null || pageContext == null) {
            return;
        }
        String articleId = pageContext.getArticleId();
        if (articleId == null || articleId.isBlank()) {
            return;
        }
        try {
            articleSessionAnchorService.mark(sessionId, Long.valueOf(articleId.trim()),
                    ArticleSessionAnchorService.SOURCE_PAGE_ACTION);
        } catch (NumberFormatException e) {
            // 页面文章 ID 非法，不写
        }
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

    /**
     * QA 正文注入（V3.9）：resolver 单点决议结果 → 文章上下文 prompt 或追问/说明态文案。
     * target：拼文章内容注入 + 锚写点（PAGE_QA，同 id 幂等）；promptNote：直接作为注入文案。
     * 文章已由 resolver 完成可读加载，这里不再查库（读权限与加载一致性收口在 resolver）。
     */
    private String buildArticleDetailContextFromQaTarget(
            QaTarget qaTarget, Long sessionId, PageContextDTO pageContext){
        if (qaTarget == null) {
            return null;
        }
        if (!qaTarget.hasArticle()) {
            return qaTarget.promptNote();
        }

        Articles article = qaTarget.article();

        // V3.8：QA 命中文章 = 用户明确围绕这篇文章 → 会话文章锚写点（PAGE_QA）。
        // 游客流 sessionId 为 null 不写（会话锚无归属意义）。
        if (sessionId != null) {
            articleSessionAnchorService.mark(sessionId, article.getId(),
                    ArticleSessionAnchorService.SOURCE_PAGE_QA);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("用户询问的文章内容：\n");
        sb.append("标题：").append(article.getTitle()).append("\n");

        if (article.getSummary() != null && !article.getSummary().isBlank()) {
            sb.append("摘要：").append(article.getSummary()).append("\n");
        }

        sb.append("正文：\n")
                .append(limitText(article.getContent(), 9000))
                .append("\n");

        sb.append("\n请基于以上文章内容回答用户问题，不要编造文章中没有的信息。");
        sb.append("回答中的关键结论后面请使用来源编号 [1]，因为这篇文章会作为参考来源 [1] 展示。");

        // 编辑器页：正文来自数据库已保存版本，编辑器里的未保存修改不存在于任何地方。
        // 不说清楚 → 用户问「我写的那段呢」，AI 直接断言文章里没有。
        if (pageContext != null && "editor-edit".equals(pageContext.getPageType())) {
            sb.append("\n注意：以上正文来自已保存版本，用户在编辑器里可能还有未保存的修改——");
            sb.append("如果用户问的内容在正文中找不到，先说明「可能是还没有保存」，不要直接断言文章里没有。");
        }

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

    /**
     * QA 来源卡（V3.9）：resolver 单点决议结果 → ArticleRagContext（来源 [1]）或 null。
     * 追问态/说明态无文章可引 → null（ragContexts 空，正文注入位已带 promptNote）。
     */
    private ArticleRagContext buildCurrentArticleReferenceFromQaTarget(QaTarget qaTarget) {
        if (qaTarget == null || !qaTarget.hasArticle()) {
            return null;
        }

        Articles article = qaTarget.article();

        StringBuilder snippet = new StringBuilder();

        snippet.append("用户询问的文章内容：\n");
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

        // V3.8：会话消息删光 = 会话里不再有"刚刚/那篇"可指 → 清会话文章锚。
        // （实测 2026-09-07：用户删光消息后继续发"把刚刚那篇隐藏了"，锚仍指向已删对话里的文章，
        // 造成"删光了 AI 还记得"的困惑；消息归零则锚归零，符合"清空对话 = 上下文归零"的心智。）
        clearArticleAnchorIfSessionEmpty(sessionId);
    }

    /**
     * V3.8：会话内消息数为 0 时清空文章锚（含全部消息被逐条删除的场景）。
     * V3.12：**结论锚一并清**——否则用户清空聊天记录后，系统仍会继承已删除对话里的优化建议，
     * 与 V3.8 建立的「清空对话 = 上下文归零」语义冲突。
     */
    private void clearArticleAnchorIfSessionEmpty(Long sessionId) {
        Long remaining = lambdaQuery()
                .eq(AiMessages::getSessionId, sessionId)
                .count();
        if (remaining != null && remaining > 0) {
            return;
        }
        aiSessionService.lambdaUpdate()
                .eq(AiSessions::getId, sessionId)
                .set(AiSessions::getLastArticleId, null)
                .set(AiSessions::getLastArticleTitle, null)
                .set(AiSessions::getLastArticleSource, null)
                .set(AiSessions::getLastArticleUpdatedAt, null)
                .set(AiSessions::getLastConclusionArticleId, null)
                .set(AiSessions::getLastConclusionText, null)
                .set(AiSessions::getLastConclusionSourceRunId, null)
                .set(AiSessions::getLastConclusionSourceType, null)
                .set(AiSessions::getLastConclusionUpdatedAt, null)
                .update();
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
