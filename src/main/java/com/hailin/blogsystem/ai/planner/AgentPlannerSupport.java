package com.hailin.blogsystem.ai.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.AiSessions;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.dto.AgentAction;
import com.hailin.blogsystem.entity.dto.AgentDecision;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.CtaKind;
import com.hailin.blogsystem.entity.dto.AiWorkflowType;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.ai.agent.AgentRuntimeRouteRegistry;
import com.hailin.blogsystem.ai.agent.ArticleSessionAnchorService;
import com.hailin.blogsystem.mapper.AiWorkflowRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class AgentPlannerSupport {

    private static final String TOOL_DASHBOARD = "getLearningDashboard";

    private static final String RETRIEVAL_NONE = "NONE";
    private static final String RETRIEVAL_ARTICLE_SEARCH = "ARTICLE_SEARCH";
    private static final String RETRIEVAL_CURRENT_ARTICLE = "CURRENT_ARTICLE";

    private static final Set<String> ARTICLE_ACTION_TYPES = Set.of(
            "likeArticle",
            "unlikeArticle",
            "favoriteArticle",
            "unfavoriteArticle",
            "followAuthor",
            "unfollowAuthor",
            "commentArticle",
            "copyArticleLink",
            "scrollToComments",
            "scrollToTop"
    );

    private static final Set<String> NAVIGATION_TARGETS = Set.of(
            "home",
            "profile",
            "editor",
            "drafts",
            "hotRank",
            "article",
            "userProfile"
    );

    private static final Set<String> EDITOR_ACTION_TYPES = Set.of(
            "saveDraft",
            "publish"
    );

    private final BlogAiProperties properties;
    private final AiWorkflowRunMapper workflowRunMapper;
    private final ObjectMapper objectMapper;
    private final AgentRuntimeRouteRegistry agentRuntimeRouteRegistry;
    private final ArticleSessionAnchorService articleSessionAnchorService;

    /**
     * 全域 Agent Planner 入口。
     *
     * active Workflow 优先由 AiMessageServiceImpl 提前处理，
     * 进入这里时说明当前没有活跃 Workflow。
     *
     * 这一轮只负责：
     * 1. 消费 LLM 分类建议
     * 2. 做后端确定性裁决
     * 3. 输出统一 AgentDecision
     *
     * 暂时不负责真正执行 Workflow、RAG 或前端动作。
     */
    public AgentDecision decide(
            String message,
            AiIntent intent,
            PageContextDTO pageContext,
            Long userId,
            AiSessions session
    ) {
        /*
         * Agent Runtime 路由（LEARNING_AGENT）。
         *
         * 独立于学习 Workflow 管道：isLearningDomain 不包含 LEARNING_AGENT，
         * 因此即使后端正则（如“帮我+学习”）命中，也不会被拉进
         * LEARNING_PLAN / LEARNING_PROGRESS / LEARNING_ASSIST。
         * Agent 只读 Loop 误入无害，漏检掉普通聊天也安全。
         */
        if (isLearningAgentIntent(intent)) {
            return decideLearningAgent(message, intent);
        }

        /*
         * 文章 Agent 路由（V2.5，ARTICLE_AGENT）。
         *
         * 与 LEARNING_AGENT 同理独立于 Workflow 管道：isLearningDomain 不包含它，
         * ARTICLE_AGENT 也不会被 resolveArticleWorkflowType 误判成 OPTIMIZE_ARTICLE。
         */
        if (isArticleAgentIntent(intent)) {
            return decideArticleAgent(intent, pageContext, session, userId);
        }

        /*
         * 兼容现有学习线。
         *
         * 当前学习查询仍有一部分历史规则兜底，
         * 先保持现有 Learning Agent V1 行为不变。
         * 后续全域入口接通后，再删除这里对旧学习规则的兼容依赖。
         */
        if (isLearningDomain(intent)) {
            return decideLearning(intent, userId, session);
        }

        if (intent == null) {
            return chat(
                    null,
                    RETRIEVAL_NONE,
                    List.of(),
                    "分类器没有返回有效意图，降级普通聊天"
            );
        }

        /*
         * CTA 是模型明确提出的澄清建议。
         * 后端不应该把 CTA 强行升级成 Workflow。
         */
        if (isCtaSuggestion(intent)) {
            return cta(
                    intent.getIntent(),
                    List.of(),
                    "分类器建议 CTA，等待用户进一步澄清",
                    CtaKind.AMBIGUOUS_REQUEST
            );
        }

        if ("ARTICLE_ACTION".equals(intent.getIntent())) {
            return decideArticleAction(intent, pageContext, userId);
        }

        if ("NAVIGATE".equals(intent.getIntent())) {
            return decideNavigate(intent, pageContext);
        }

        if ("EDITOR_ACTION".equals(intent.getIntent())) {
            return decideEditorAction(intent, pageContext, userId);
        }

        /*
         * 文章查询属于 CHAT + RAG，
         * 不是模型直接调用文章 Tool。
         */
        if ("ARTICLE_SEARCH".equals(intent.getIntent())) {
            return chat(
                    intent.getIntent(),
                    RETRIEVAL_ARTICLE_SEARCH,
                    List.of("article_search_intent"),
                    "用户明确查询站内文章，使用文章 RAG"
            );
        }

        /*
         * 当前文章问答（V3.9）：恒放行文章检索模式，不预判目标——
         * QA 的决策形态与目标无关（都是 CHAT），目标由 ArticleQaTargetResolver 单点决议
         * （页面文章 / 会话锚 / 追问态 / 说明态），Planner 预判会造成与 resolver 的割裂。
         * RETRIEVAL_CURRENT_ARTICLE 只是触发 QA 消费点的开关，不代表真有目标；
         * 无目标时 resolver 产出 promptNote（正文注入位），不是无提示普通聊。
         */
        if ("ARTICLE_DETAIL_QA".equals(intent.getIntent())) {
            return chat(
                    intent.getIntent(),
                    RETRIEVAL_CURRENT_ARTICLE,
                    List.of("article_detail_qa_intent"),
                    "用户询问某篇文章内容，检索模式放行，目标由 QA resolver 单点决议"
            );
        }

        /*
         * 文章创作和文章优化进入统一 Workflow 裁决。
         */
        AiWorkflowType articleWorkflowType =
                resolveArticleWorkflowType(intent);

        if (articleWorkflowType != null) {
            return decideArticleWorkflow(
                    intent,
                    articleWorkflowType,
                    pageContext
            );
        }

        /*
         * 通用思考模式（V3，GENERAL_CHAT + needsThinking）。
         *
         * 归一化收在 Planner 单点：needsThinking 只作用于 GENERAL_CHAT，
         * 且必须分类器语义判定为 true（LLM 判错也不影响其他意图）。
         * 游客恒 false（无记忆、无归属，不进通用 Runtime）。
         * AiMessageServiceImpl 只消费 AgentDecision，不重复判断。
         */
        if (isGeneralAgentCandidate(intent)) {
            return decideGeneralAgent(intent, userId);
        }

        /*
         * 其他暂未迁移的页面动作先继续走普通聊天旧执行链。
         * 下一轮再把 clientActionType 统一接入。
         */
        return chat(
                intent.getIntent(),
                RETRIEVAL_NONE,
                List.of(),
                "未命中统一 Workflow 或 RAG 路由，走普通聊天"
        );
    }

    public AgentDecision decideLearning(AiIntent intent, Long userId, AiSessions session){
        List<String> ruleHits = new ArrayList<>();

        if (isCtaSuggestion(intent)) {
            return cta(
                    intent == null ? null : intent.getIntent(),
                    ruleHits,
                    "分类器建议 CTA，等待用户进一步澄清",
                    CtaKind.AMBIGUOUS_REQUEST
            );
        }

        AiWorkflowType workflowType = resolveLearningWorkflowType(intent, ruleHits);
        if (workflowType == null) {
            /*
             * 没有工作流意图时，看是不是纯粹的「查询已有计划」→ dashboard 读工具。
             *
             * 位置很关键：必须在 resolveLearningWorkflowType **之后**。
             * 主字段是 LEARNING_PLAN_QUERY 但建议字段指向真实工作流时，那一步会把它救回成 WORKFLOW
             * （见 workflowSuggestionCanPassWhenIntentIsMisclassifiedButSuggestedTypeMatches）；
             * 这里只承接"确实没有工作流诉求"的查询句。
             *
             * V4.x：这个分流原先用字面正则（看看/查/进度 + 计划）实现，且写在**分类器之前**，
             * 于是「你帮我分析一下我的c++学习计划，看看这份计划有没有问题」被截胡成"查询"——
             * 分类器明明判对了 LEARNING_ASSIST（分析评估），却被"看看…计划"的字面命中推翻，
             * 结果不进工作流、也没有任何过程提示（用户只看到干等后突然出一大段）。
             * 原测试名写的是「EvenWhenClassifierMisjudgesPlanIntent」（分类器判错时兜底），
             * 实现却是"无条件优先"——兜底与抢跑的区别就在这里。正则整体删除，语义判断交还分类器。
             */
            if (intent != null && "LEARNING_PLAN_QUERY".equals(intent.getIntent())) {
                ruleHits.add("learning_plan_query");
                return AgentDecision.builder()
                        .action(AgentAction.TOOL)
                        .intent(intent.getIntent())
                        .toolName(TOOL_DASHBOARD)
                        .retrievalMode(RETRIEVAL_NONE)
                        .ruleHits(ruleHits)
                        .reason("分类器判定为学习计划查询，使用 dashboard 读工具")
                        .build();
            }

            // 分类器说了 WORKFLOW，却拿不出任何学习类 Workflow 类型
            // （intent 与 suggestedWorkflowType 两个字段都落空）。
            // 这里不能直接 CHAT，否则分类器的 WORKFLOW 建议被静默吞掉。
            // 注：措辞已随 V4.x 词表移除更新——此前写「后端规则未命中」，
            // 现在后端不再做字面复核，失配只可能来自分类器自身。
            if (isWorkflowSuggestion(intent)) {
                return cta(
                        intent == null ? null : intent.getIntent(),
                        ruleHits,
                        "分类器建议启动 Workflow 但未指明学习类类型，降级 CTA"
                );
            }

            return AgentDecision.builder()
                    .action(AgentAction.CHAT)
                    .intent(intent == null ? null : intent.getIntent())
                    .retrievalMode(RETRIEVAL_NONE)
                    .ruleHits(ruleHits)
                    .reason("未命中学习 Agent 决策，走普通聊天")
                    .build();
        }

        /*
         * V4.x：会话学习计划锚命中时，MEDIUM 不再拦（仅限"操作已有计划"的意图）。
         *
         * 分类器判 MEDIUM 的典型理由正是「计划对象不清楚」（它的 risk 定义里就有这条），
         * 而锚解决的恰好是这件事——上一轮已经定位到具体计划，这一轮省略主语也有据可依。
         * 代价可控：放行不等于改数据，工作流本身有确认门，最高代价是多弹一张可确认的卡。
         * 锚失效（计划已删/不是本人）不会误放行——下游 resolve 返回 null，仍走原有追问。
         */
        if (!isLowRisk(intent) && !hasPlanAnchorFor(session, workflowType)) {
            return cta(
                    intent == null ? null : intent.getIntent(),
                    ruleHits,
                    "分类器风险不是 LOW，降级 CTA"
            );
        }
        if (!hasConfidence(intent)) {
            return cta(
                    intent == null ? null : intent.getIntent(),
                    ruleHits,
                    "分类器置信度不足，降级 CTA"
            );
        }
        if (!hasWorkflowSuggestion(intent, workflowType)) {
            return cta(
                    intent == null ? null : intent.getIntent(),
                    ruleHits,
                    "分类器未建议对应 Workflow，降级 CTA"
            );
        }
        if (autoStartCountReached(userId, session)) {
            return cta(
                    intent == null ? null : intent.getIntent(),
                    ruleHits,
                    "本会话自动拉起次数已达上限，降级 CTA",
                    CtaKind.QUOTA_REACHED
            );
        }

        return AgentDecision.builder()
                .action(AgentAction.WORKFLOW)
                .intent(intent == null ? null : intent.getIntent())
                .workflowType(workflowType)
                .retrievalMode(RETRIEVAL_NONE)
                .ruleHits(ruleHits)
                .reason("LLM 建议 + 后端规则双签命中，自动拉起 Workflow")
                .build();
    }

    /**
     * LEARNING_AGENT 的后端裁决。
     *
     * 入口规则不做正则兜底（漏检掉普通聊天安全，误检只读无害），
     * 只认分类器主判 + 建议一致：
     * - 分类器建议 CTA → 保持 CTA（澄清优先）
     * - 分类器建议 AGENT → 进入 Agent Runtime
     * - 其余（分类器内部不一致）→ 普通聊天
     */
    private AgentDecision decideLearningAgent(String message, AiIntent intent) {
        List<String> ruleHits = new ArrayList<>();

        if (isCtaSuggestion(intent)) {
            return cta(
                    intent.getIntent(),
                    ruleHits,
                    "分类器建议 CTA，等待用户进一步澄清",
                    CtaKind.AMBIGUOUS_REQUEST
            );
        }

        if (!"AGENT".equals(intent.getSuggestedAction())) {
            return AgentDecision.builder()
                    .action(AgentAction.CHAT)
                    .intent(intent.getIntent())
                    .retrievalMode(RETRIEVAL_NONE)
                    .ruleHits(ruleHits)
                    .reason("分类器意图 LEARNING_AGENT 但建议不一致，走普通聊天")
                    .build();
        }

        ruleHits.add("learning_agent_intent");
        ruleHits.add("learning_agent_suggestion_valid");

        return AgentDecision.builder()
                .action(AgentAction.AGENT)
                .intent(intent.getIntent())
                .retrievalMode(RETRIEVAL_NONE)
                .ruleHits(ruleHits)
                .reason("分类器主判 LEARNING_AGENT，进入 Agent Runtime")
                .build();
    }

    /**
     * ARTICLE_AGENT 的后端裁决（V2.5）。
     *
     * 与 LEARNING_AGENT 同构，多一道文章上下文校验：
     * - 分类器建议 CTA → 保持 CTA（澄清优先）
     * - 分类器建议 AGENT + 有文章上下文 → 进入文章 Agent Runtime
     * - 分类器建议 AGENT 但缺 articleId → CTA（用户大概率真想优化文章，引导补充信息）
     * - 其余（分类器内部不一致）→ 普通聊天
     */
    private AgentDecision decideArticleAgent(AiIntent intent, PageContextDTO pageContext,
                                             AiSessions session, Long userId) {
        List<String> ruleHits = new ArrayList<>();

        if (isCtaSuggestion(intent)) {
            return cta(
                    intent.getIntent(),
                    ruleHits,
                    "分类器建议 CTA，等待用户进一步澄清",
                    CtaKind.AMBIGUOUS_REQUEST
            );
        }

        if (!"AGENT".equals(intent.getSuggestedAction())) {
            return AgentDecision.builder()
                    .action(AgentAction.CHAT)
                    .intent(intent.getIntent())
                    .retrievalMode(RETRIEVAL_NONE)
                    .ruleHits(ruleHits)
                    .reason("分类器意图 ARTICLE_AGENT 但建议不一致，走普通聊天")
                    .build();
        }

        // V3.8：信源收紧——页面文章只看后端 pageContext（分类器 intent.articleId 是 LLM 输出，
        // 不参与定位，防幻觉 ID 绕过 CTA）。文章域可信文章源 = pageContext ∪ 会话锚（都是后端事件）。
        if (!hasPageArticle(pageContext)) {
            ruleHits.add("article_context_missing");
            // V3.8：页面无文章 → 会话文章锚兜底（resolve 已校验文章存在 + 归属本人）。
            // 放行 = 只给候选，目标由 runtime 首步 anchorMode 消解，不在 Planner 定死。
            // 2026-09-10 手测修正：锚读改**可读语义**（他人公开文章也算）——原 owned 语义会让
            // 聊过别人文章后说「刚刚那篇」直接被 CTA 拦住（读得到却进不去）。写路径归属校验不受影响。
            Articles anchor = articleSessionAnchorService.resolveReadable(
                    session == null ? null : session.getId(), userId);
            if (anchor != null) {
                ruleHits.add("session_article_anchor_resolved");
                return AgentDecision.builder()
                        .action(AgentAction.AGENT)
                        .intent(intent.getIntent())
                        .retrievalMode(RETRIEVAL_NONE)
                        .ruleHits(ruleHits)
                        .reason("分类器主判 ARTICLE_AGENT，会话文章锚命中，进入文章 Agent Runtime")
                        .build();
            }
            return cta(
                    intent.getIntent(),
                    ruleHits,
                    "你说的这篇文章在这段对话里还没有出现过。打开那篇文章的详情页，"
                            + "再对我说\"把这篇…\"，我就能直接帮你处理。",
                    CtaKind.MISSING_ARTICLE_CONTEXT
            );
        }

        ruleHits.add("article_agent_intent");
        ruleHits.add("article_agent_suggestion_valid");
        ruleHits.add("article_context_valid");

        return AgentDecision.builder()
                .action(AgentAction.AGENT)
                .intent(intent.getIntent())
                .retrievalMode(RETRIEVAL_NONE)
                .ruleHits(ruleHits)
                .reason("分类器主判 ARTICLE_AGENT，进入文章 Agent Runtime")
                .build();
    }

    /**
     * GENERAL_CHAT + needsThinking 的后端裁决（V3 通用思考模式）。
     *
     * 归一化唯一入口：needsThinking 只在这里被消费，
     * AiMessageServiceImpl 不感知该字段（只按 AgentDecision.action 分发）。
     * - 分类器建议 CTA → 保持 CTA（澄清优先）
     * - 分类器建议不一致 → 普通聊天
     * - 游客（userId == null）→ 恒 false，不进通用 Runtime（无记忆、无归属）
     * - 分类器 needsThinking=true + 已登录 → 进入通用 Agent Runtime
     */
    private AgentDecision decideGeneralAgent(AiIntent intent, Long userId) {
        List<String> ruleHits = new ArrayList<>();

        if (isCtaSuggestion(intent)) {
            return cta(
                    intent.getIntent(),
                    ruleHits,
                    "分类器建议 CTA，等待用户进一步澄清"
            );
        }

        if (!"CHAT".equals(intent.getSuggestedAction())) {
            return AgentDecision.builder()
                    .action(AgentAction.CHAT)
                    .intent(intent.getIntent())
                    .retrievalMode(RETRIEVAL_NONE)
                    .ruleHits(ruleHits)
                    .reason("分类器意图 GENERAL_CHAT 但建议不一致，走普通聊天")
                    .build();
        }

        if (userId == null) {
            ruleHits.add("guest_needs_thinking_forbidden");
            return AgentDecision.builder()
                    .action(AgentAction.CHAT)
                    .intent(intent.getIntent())
                    .retrievalMode(RETRIEVAL_NONE)
                    .ruleHits(ruleHits)
                    .reason("游客不进入通用 Agent Runtime，走普通聊天")
                    .build();
        }

        ruleHits.add("general_agent_needs_thinking");
        ruleHits.add("general_agent_user_valid");

        return AgentDecision.builder()
                .action(AgentAction.AGENT)
                .intent(intent.getIntent())
                .retrievalMode(RETRIEVAL_NONE)
                .ruleHits(ruleHits)
                .reason("GENERAL_CHAT 且需要结合记忆/上下文先查再答，进入通用 Agent Runtime")
                .build();
    }

    /**
     * CTA 缺省分类：系统自身不确定（置信 / 风险 / 字段不一致 / 白名单外）。
     * 用户对这些原因无从行动，文案层只给中性引导，不解释内部机制。
     */
    private AgentDecision cta(
            String intent,
            List<String> ruleHits,
            String reason
    ) {
        return cta(intent, ruleHits, reason, CtaKind.SYSTEM_UNCERTAIN);
    }

    /** CTA 指定面向用户的分类（V4.x：reason 只作诊断，用户文案走 ctaKind） */
    private AgentDecision cta(
            String intent,
            List<String> ruleHits,
            String reason,
            CtaKind ctaKind
    ) {
        return AgentDecision.builder()
                .action(AgentAction.CTA)
                .intent(intent)
                .retrievalMode(RETRIEVAL_NONE)
                .ruleHits(ruleHits)
                .reason(reason)
                .ctaKind(ctaKind)
                .build();
    }

    private AgentDecision chat(
            String intent,
            String retrievalMode,
            List<String> ruleHits,
            String reason
    ) {
        return AgentDecision.builder()
                .action(AgentAction.CHAT)
                .intent(intent)
                .retrievalMode(retrievalMode)
                .ruleHits(ruleHits)
                .reason(reason)
                .build();
    }

    /**
     * 学习类 intent → Workflow 类型。**只认分类器输出，不对用户原话做字面复核**（V4.x 决策）。
     *
     * 这里曾对 message 做「动作词/难度词 + 计划名词」的正则校验，只有"长得像"才放行，否则降级 CTA。
     * 实践证伪：用字面匹配复核语义判断，等于让更弱的能力否决更强的能力——分类器已判对 intent、
     * 计划与阶段，却被"你的用词不在词表里"退回（实测：「压缩」在表内、「浓缩」不在 → 明确诉求降成 CTA）。
     * 词表是有限闭集、表达是开放集，结构上补不完（补了「浓缩」还有「精简/梳理/补充几个/换一批」）；
     * 其内容实质是"历史踩坑清单"，只认曾经出过问题的说法。
     *
     * 两个字段的用法（两侧行为都有测试锁定，改动前先看那两个用例）：
     * - **主字段 intent 优先**——它是分类器对"这是什么诉求"的正面判断。主字段是学习类时以它为准；
     *   若与 suggestedWorkflowType 打架（LEARNING_PLAN vs LEARNING_PROGRESS），交给下游
     *   hasWorkflowSuggestion 判不一致 → 降级 CTA——两个字段互相矛盾时不猜哪个对。
     * - **主字段没落在学习 Workflow 上时，退到建议字段救回**——分类器把主意图判成
     *   LEARNING_PLAN_QUERY / GENERAL_CHAT 但建议字段明确指向某个学习 Workflow 时仍应拉起
     *   （见 workflowSuggestionCanPassWhenIntentIsMisclassifiedButSuggestedTypeMatches）。
     *
     * 后端该判的是**事实**而非**语义**：有没有 ACTIVE 计划、计划是不是本人的、定位不到怎么办、
     * 写操作是否经过确认——这些在 routeLearningXxxWorkflow 与工作流确认门里已经具备。
     * 误放行的最高代价是"多弹一张可取消的确认卡"，误拦截的代价是用户明确诉求被降级。
     */
    private AiWorkflowType resolveLearningWorkflowType(AiIntent intent, List<String> ruleHits) {
        if (intent == null) {
            return null;
        }
        AiWorkflowType declared = learningWorkflowTypeOf(intent.getIntent(), ruleHits);
        return declared != null
                ? declared
                : learningWorkflowTypeOf(intent.getSuggestedWorkflowType(), ruleHits);
    }

    //分类器字段值 → 学习类 Workflow 类型（命中才记 ruleHit）；非学习类返回 null
    private AiWorkflowType learningWorkflowTypeOf(String value, List<String> ruleHits) {
        if ("LEARNING_ASSIST".equals(value)) {
            ruleHits.add("learning_assist_rule");
            return AiWorkflowType.LEARNING_ASSIST;
        }
        if ("LEARNING_PROGRESS".equals(value)) {
            ruleHits.add("learning_progress_rule");
            return AiWorkflowType.LEARNING_PROGRESS;
        }
        if ("LEARNING_PLAN".equals(value)) {
            ruleHits.add("learning_plan_rule");
            return AiWorkflowType.LEARNING_PLAN;
        }
        return null;
    }

    /**
     * 判断 LLM 是否提出了 Workflow 建议。
     *
     * 这里只看建议，不代表最终允许执行。
     */
    private boolean isWorkflowSuggestion(AiIntent intent) {
        return intent != null
                && "WORKFLOW".equals(intent.getSuggestedAction());
    }

    private boolean isCtaSuggestion(AiIntent intent) {
        return intent != null
                && "CTA".equals(intent.getSuggestedAction());
    }

    private boolean hasWorkflowSuggestion(AiIntent intent, AiWorkflowType workflowType) {
        return intent != null
                && "WORKFLOW".equals(intent.getSuggestedAction())
                && workflowType.name().equals(intent.getSuggestedWorkflowType());
    }

    private boolean hasConfidence(AiIntent intent) {
        double threshold = properties.getAgent().getAutoStartConfidenceThreshold();
        return intent != null && intent.getConfidence() != null && intent.getConfidence() >= threshold;
    }

    private boolean isLowRisk(AiIntent intent) {
        return intent != null && "LOW".equals(intent.getRisk());
    }

    /**
     * 会话学习计划锚能否为「风险」兜底（V4.x）——**只对"操作已有计划"的意图生效**。
     *
     * 锚解答的是"改哪个计划"这个问题，所以：
     * - LEARNING_PROGRESS / LEARNING_ASSIST 需要定位目标 → 锚有意义 ✓
     * - LEARNING_PLAN（新建）不涉及"改哪个" → 锚帮不上忙，仍按 MEDIUM 降级 ✗
     *
     * 只看字段非空，**不在 Planner 里查库**（保持它无 IO）：锚指向的计划是否还存在、
     * 是否属于本人，由下游 {@code LearningPlanAnchorService.resolve} 单点校验——
     * 校验不过时那条路会退回原有的追问行为，不会因为这里的放行而出错。
     */
    private boolean hasPlanAnchorFor(AiSessions session, AiWorkflowType workflowType) {
        if (workflowType != AiWorkflowType.LEARNING_PROGRESS
                && workflowType != AiWorkflowType.LEARNING_ASSIST) {
            return false;
        }
        return session != null && session.getLastLearningPlanId() != null;
    }

    private boolean autoStartCountReached(Long userId, AiSessions session) {
        if (userId == null || session == null || session.getId() == null) {
            return false;
        }
        int count = workflowRunMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiWorkflowRun>()
                        .eq(AiWorkflowRun::getUserId, userId)
                        .eq(AiWorkflowRun::getConversationId, session.getId()))
                .stream()
                .filter(this::isAgentAutoStarted)
                .toList()
                .size();

        return count >= properties.getAgent().getAutoStartLimitPerSession();
    }

    private boolean isAgentAutoStarted(AiWorkflowRun run) {
        try {
            JsonNode node = objectMapper.readTree(run.getContextJson());
            return node.path("agentAutoStarted").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判断是否进入学习 Agent Runtime（LEARNING_AGENT）。
     *
     * 只认分类器主判，不做正则兜底（判定名单单点在 AgentRuntimeRouteRegistry，V3.5 收口）。
     * 注意：LEARNING_AGENT 故意不放进 isLearningDomain，
     * 从而与学习 Workflow 管道隔离（见 decide() 注释）。
     */
    private boolean isLearningAgentIntent(AiIntent intent) {
        return intent != null
                && agentRuntimeRouteRegistry.learningAgentIntents().contains(intent.getIntent());
    }

    /**
     * 判断是否进入文章 Agent Runtime（ARTICLE_AGENT，V2.5）。
     *
     * 只认分类器主判，不做正则兜底（判定名单单点在 AgentRuntimeRouteRegistry，V3.5 收口）。
     * 注意：ARTICLE_AGENT 故意不放进任何 Workflow 管道，
     * 与 LEARNING_AGENT 同理隔离（见 decide() 注释）。
     */
    private boolean isArticleAgentIntent(AiIntent intent) {
        return intent != null
                && agentRuntimeRouteRegistry.articleAgentIntents().contains(intent.getIntent());
    }

    /**
     * 是否进入通用 Agent Runtime（GENERAL_CHAT + needsThinking，V3）。
     *
     * 只认分类器主判，不做正则兜底（与 LEARNING_AGENT / ARTICLE_AGENT 同模式）。
     * needsThinking 为 null 视为 false（后端裁判，不信任 LLM 缺失值）。
     */
    private boolean isGeneralAgentCandidate(AiIntent intent) {
        return intent != null
                && "GENERAL_CHAT".equals(intent.getIntent())
                && Boolean.TRUE.equals(intent.getNeedsThinking());
    }

    /**
     * 判断当前分类结果是否属于学习领域。
     *
     * 注意：这里只看分类器已经给出的结构化意图，
     * 不在这里重新用正则猜测自然语言。
     */
    private boolean isLearningDomain(AiIntent intent) {
        if (intent == null) {
            return false;
        }

        return "LEARNING_PLAN".equals(intent.getIntent())
                || "LEARNING_PROGRESS".equals(intent.getIntent())
                || "LEARNING_ASSIST".equals(intent.getIntent())
                || "LEARNING_PLAN_QUERY".equals(intent.getIntent())
                || "LEARNING_PLAN".equals(intent.getSuggestedWorkflowType())
                || "LEARNING_PROGRESS".equals(intent.getSuggestedWorkflowType())
                || "LEARNING_ASSIST".equals(intent.getSuggestedWorkflowType());
    }

    /**
     * 只解析文章相关 Workflow。
     *
     * 学习 Workflow 仍由 decideLearning 处理，
     * 避免这一轮改动影响已经通过的 12 个测试。
     */
    private AiWorkflowType resolveArticleWorkflowType(AiIntent intent) {
        if (intent == null) {
            return null;
        }

        String suggestedType = intent.getSuggestedWorkflowType();

        if ("CREATE_ARTICLE".equals(suggestedType)
                || "CREATE_ARTICLE_WORKFLOW".equals(intent.getIntent())) {
            return AiWorkflowType.CREATE_ARTICLE;
        }

        if ("OPTIMIZE_ARTICLE".equals(suggestedType)
                || "OPTIMIZE_ARTICLE_WORKFLOW".equals(intent.getIntent())) {
            return AiWorkflowType.OPTIMIZE_ARTICLE;
        }

        return null;
    }

    /**
     * 文章 Workflow 的后端裁决。
     *
     * 这里不再用 looksLikeCreateArticleRequest、
     * looksLikeOptimizeArticleRequest 判断整句话语义。
     *
     * 语义由 LLM 分类器提供；
     * 后端只检查建议是否合法、置信度、风险和页面资源。
     */
    private AgentDecision decideArticleWorkflow(
            AiIntent intent,
            AiWorkflowType workflowType,
            PageContextDTO pageContext
    ) {
        List<String> ruleHits = new ArrayList<>();

        if (!isWorkflowSuggestion(intent)) {
            return cta(
                    intent.getIntent(),
                    ruleHits,
                    "分类器没有建议启动 Workflow，降级 CTA"
            );
        }

        if (!hasWorkflowSuggestion(intent, workflowType)) {
            return cta(
                    intent.getIntent(),
                    ruleHits,
                    "分类器建议的 Workflow 类型不一致，降级 CTA"
            );
        }

        if (!hasConfidence(intent)) {
            return cta(
                    intent.getIntent(),
                    ruleHits,
                    "分类器置信度不足，降级 CTA"
            );
        }

        /*
         * 文章优化必须有当前文章上下文。
         * 不能让 LLM 猜 articleId。
         */
        if (workflowType == AiWorkflowType.OPTIMIZE_ARTICLE
                && !hasArticleId(pageContext, intent)) {
            ruleHits.add("article_context_missing");

            return cta(
                    intent.getIntent(),
                    ruleHits,
                    "文章优化缺少当前文章 ID，降级 CTA",
                    CtaKind.MISSING_ARTICLE_CONTEXT
            );
        }

        /*
         * 文章创作和文章优化本身都是确认型 Workflow。
         *
         * 创建 Workflow 不等于立即写入文章，
         * 真正生成、填充、保存仍然由 Workflow 确认节点控制。
         *
         * 因此这里允许 MEDIUM 风险，
         * 但 HIGH 风险不能自动拉起。
         */
        if (!isArticleWorkflowRiskAllowed(intent)) {
            return cta(
                    intent.getIntent(),
                    ruleHits,
                    "文章 Workflow 风险过高，降级 CTA"
            );
        }

        ruleHits.add("workflow_suggestion_valid");
        ruleHits.add("workflow_type_valid");

        if (workflowType == AiWorkflowType.OPTIMIZE_ARTICLE) {
            ruleHits.add("article_context_valid");
        }

        return AgentDecision.builder()
                .action(AgentAction.WORKFLOW)
                .intent(intent.getIntent())
                .workflowType(workflowType)
                .retrievalMode(RETRIEVAL_NONE)
                .ruleHits(ruleHits)
                .reason("LLM 建议 + 后端资源规则通过，允许进入文章 Workflow")
                .build();
    }

    /**
     * 文章 Workflow 的风险判断。
     *
     * CREATE_ARTICLE：
     * - LOW / MEDIUM 都允许进入确认型 Workflow
     *
     * OPTIMIZE_ARTICLE：
     * - LOW / MEDIUM 都允许进入确认型 Workflow
     *
     * HIGH：
     * - 不允许自动拉起
     */
    private boolean isArticleWorkflowRiskAllowed(AiIntent intent) {
        if (intent == null || intent.getRisk() == null) {
            return false;
        }

        return "LOW".equals(intent.getRisk())
                || "MEDIUM".equals(intent.getRisk());
    }

    /**
     * articleId 优先使用后端页面上下文，
     * 其次才读取分类器从用户原话中提取的 articleId。
     *
     * 这里仅判断是否存在，不在 Planner 中做最终权限裁决。
     * 文章是否存在、是否属于当前用户，继续由业务 Service 校验。
     *
     * V3.8 注意：文章域 Agent 裁决（decideArticleAgent）不再走此方法——改用 hasPageArticle
     * （只信后端 pageContext），分类器 LLM 输出的 articleId 不参与定位（防幻觉 ID 绕过会话锚兜底）。
     * QA / OPTIMIZE Workflow 等旧链路保持原语义不变。
     */
    private boolean hasArticleId(
            PageContextDTO pageContext,
            AiIntent intent
    ) {
        if (pageContext != null
                && pageContext.getArticleId() != null
                && !pageContext.getArticleId().isBlank()) {
            return true;
        }

        return intent != null
                && intent.getArticleId() != null
                && !intent.getArticleId().isBlank();
    }

    /** V3.8：页面文章是否有效（只信后端 pageContext，不采信分类器 LLM 输出）。 */
    private boolean hasPageArticle(PageContextDTO pageContext) {
        return pageContext != null
                && pageContext.getArticleId() != null
                && !pageContext.getArticleId().isBlank();
    }

    private AgentDecision decideArticleAction(
            AiIntent intent,
            PageContextDTO pageContext,
            Long userId
    ) {
        List<String> ruleHits = new ArrayList<>();

        if (!isPageActionSuggestionValid(intent)) {
            return cta(
                    intent.getIntent(),
                    ruleHits,
                    "页面动作没有得到有效的 CHAT 建议，降级 CTA"
            );
        }

        if (!hasConfidence(intent)) {
            return cta(
                    intent.getIntent(),
                    ruleHits,
                    "页面动作置信度不足，降级 CTA"
            );
        }

        if (!isPageActionRiskAllowed(intent)) {
            return cta(
                    intent.getIntent(),
                    ruleHits,
                    "页面动作风险过高，降级 CTA"
            );
        }

        if (pageContext == null
                || !"article-detail".equals(pageContext.getPageType())) {
            ruleHits.add("article_detail_context_missing");

            return cta(
                    intent.getIntent(),
                    ruleHits,
                    "文章动作必须在文章详情页执行",
                    CtaKind.PAGE_CONTEXT_MISMATCH
            );
        }

        String actionType = trimToNull(intent.getActionType());

        if (!ARTICLE_ACTION_TYPES.contains(actionType)) {
            return cta(
                    intent.getIntent(),
                    ruleHits,
                    "文章动作类型不在后端白名单中"
            );
        }

        String articleId = firstNonBlank(
                pageContext.getArticleId(),
                intent.getArticleId()
        );

        if (!isNumericId(articleId)) {
            ruleHits.add("article_id_invalid");

            return cta(
                    intent.getIntent(),
                    ruleHits,
                    "缺少有效的文章 ID，不能执行文章动作"
            );
        }

        if ("commentArticle".equals(actionType)
                && trimToNull(intent.getContent()) == null) {
            return cta(
                    intent.getIntent(),
                    ruleHits,
                    "发表评论缺少评论内容"
            );
        }

        if (requiresLogin(actionType) && userId == null) {
            return cta(
                    intent.getIntent(),
                    ruleHits,
                    "该文章动作需要登录"
            );
        }

        ruleHits.add("article_detail_context_valid");
        ruleHits.add("article_action_type_valid");
        ruleHits.add("article_id_valid");

        return AgentDecision.builder()
                .action(AgentAction.CHAT)
                .intent(intent.getIntent())
                .clientActionType(actionType)
                .retrievalMode(RETRIEVAL_NONE)
                .ruleHits(ruleHits)
                .reason("页面上下文、文章动作和文章 ID 校验通过")
                .build();
    }

    private AgentDecision decideNavigate(
            AiIntent intent,
            PageContextDTO pageContext
    ) {
        List<String> ruleHits = new ArrayList<>();

        if (!isPageActionSuggestionValid(intent)) {
            return cta(
                    intent.getIntent(),
                    ruleHits,
                    "导航动作没有得到有效的 CHAT 建议，降级 CTA"
            );
        }

        if (!hasConfidence(intent)) {
            return cta(
                    intent.getIntent(),
                    ruleHits,
                    "导航动作置信度不足，降级 CTA"
            );
        }

        if (!isPageActionRiskAllowed(intent)) {
            return cta(
                    intent.getIntent(),
                    ruleHits,
                    "导航动作风险过高，降级 CTA"
            );
        }

        String target = trimToNull(intent.getTarget());

        if (!NAVIGATION_TARGETS.contains(target)) {
            return cta(
                    intent.getIntent(),
                    ruleHits,
                    "导航目标不在后端白名单中"
            );
        }

        String param = trimToNull(intent.getParam());

        if ("article".equals(target)
                && param == null
                && pageContext != null) {
            param = trimToNull(pageContext.getArticleId());
        }

        if ("userProfile".equals(target)
                && param == null
                && pageContext != null) {
            param = firstNonBlank(
                    pageContext.getAuthorId(),
                    pageContext.getUserId()
            );
        }

        if (requiresNavigateParam(target)
                && !isNumericId(param)) {
            ruleHits.add("navigate_param_missing");

            return cta(
                    intent.getIntent(),
                    ruleHits,
                    "该导航目标缺少有效参数"
            );
        }

        ruleHits.add("navigate_target_valid");

        if (requiresNavigateParam(target)) {
            ruleHits.add("navigate_param_valid");
        }

        return AgentDecision.builder()
                .action(AgentAction.CHAT)
                .intent(intent.getIntent())
                .clientActionType("navigate")
                .retrievalMode(RETRIEVAL_NONE)
                .ruleHits(ruleHits)
                .reason("导航目标和参数校验通过")
                .build();
    }

    private AgentDecision decideEditorAction(
            AiIntent intent,
            PageContextDTO pageContext,
            Long userId
    ) {
        List<String> ruleHits = new ArrayList<>();

        if (!isPageActionSuggestionValid(intent)) {
            return cta(
                    intent.getIntent(),
                    ruleHits,
                    "编辑器动作没有得到有效的 CHAT 建议，降级 CTA"
            );
        }

        if (!hasConfidence(intent)) {
            return cta(
                    intent.getIntent(),
                    ruleHits,
                    "编辑器动作置信度不足，降级 CTA"
            );
        }

        if (!isPageActionRiskAllowed(intent)) {
            return cta(
                    intent.getIntent(),
                    ruleHits,
                    "编辑器动作风险过高，降级 CTA"
            );
        }

        String actionType = trimToNull(intent.getActionType());

        if (!EDITOR_ACTION_TYPES.contains(actionType)) {
            return cta(
                    intent.getIntent(),
                    ruleHits,
                    "编辑器动作不在后端白名单中，填充文章只能由 Workflow 触发"
            );
        }

        if (pageContext == null
                || (!"editor-new".equals(pageContext.getPageType())
                && !"editor-edit".equals(pageContext.getPageType()))) {
            ruleHits.add("editor_context_missing");

            return cta(
                    intent.getIntent(),
                    ruleHits,
                    "保存或发布必须在文章编辑器页面执行",
                    CtaKind.PAGE_CONTEXT_MISMATCH
            );
        }

        if (userId == null) {
            return cta(
                    intent.getIntent(),
                    ruleHits,
                    "保存或发布文章需要登录"
            );
        }

        ruleHits.add("editor_context_valid");
        ruleHits.add("editor_action_type_valid");

        return AgentDecision.builder()
                .action(AgentAction.CHAT)
                .intent(intent.getIntent())
                .clientActionType(actionType)
                .retrievalMode(RETRIEVAL_NONE)
                .ruleHits(ruleHits)
                .reason("编辑器页面、登录状态和动作类型校验通过")
                .build();
    }

    private boolean isPageActionSuggestionValid(AiIntent intent) {
        return intent != null
                && "CHAT".equals(intent.getSuggestedAction());
    }

    private boolean isPageActionRiskAllowed(AiIntent intent) {
        return intent != null
                && ("LOW".equals(intent.getRisk())
                || "MEDIUM".equals(intent.getRisk()));
    }

    private boolean requiresLogin(String actionType) {
        return Set.of(
                "likeArticle",
                "unlikeArticle",
                "favoriteArticle",
                "unfavoriteArticle",
                "followAuthor",
                "unfollowAuthor",
                "commentArticle"
        ).contains(actionType);
    }

    private boolean requiresNavigateParam(String target) {
        return "article".equals(target)
                || "userProfile".equals(target);
    }

    private boolean isNumericId(String value) {
        return value != null && value.matches("\\d+");
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String firstNonBlank(String first, String second) {
        String firstValue = trimToNull(first);
        if (firstValue != null) {
            return firstValue;
        }
        return trimToNull(second);
    }


}
