package com.hailin.blogsystem.ai.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.AiSessions;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.dto.AgentAction;
import com.hailin.blogsystem.entity.dto.AgentDecision;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.AiWorkflowType;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
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
            return decideArticleAgent(intent, pageContext);
        }

        /*
         * 兼容现有学习线。
         *
         * 当前学习查询仍有一部分历史规则兜底，
         * 先保持现有 Learning Agent V1 行为不变。
         * 后续全域入口接通后，再删除这里对旧学习规则的兼容依赖。
         */
        if (isLearningDomain(intent)
                || looksLikeLearningPlanQueryRequest(message)) {
            return decideLearning(message, intent, userId, session);
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
                    "分类器建议 CTA，等待用户进一步澄清"
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
         * 当前文章问答也属于 CHAT + 当前文章上下文。
         */
        if ("ARTICLE_DETAIL_QA".equals(intent.getIntent())) {
            if (!hasArticleId(pageContext, intent)) {
                return chat(
                        intent.getIntent(),
                        RETRIEVAL_NONE,
                        List.of("article_context_missing"),
                        "缺少当前文章 ID，不能执行当前文章检索"
                );
            }

            return chat(
                    intent.getIntent(),
                    RETRIEVAL_CURRENT_ARTICLE,
                    List.of("article_detail_qa_intent", "article_context_valid"),
                    "用户询问当前文章，使用当前文章上下文"
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

    public AgentDecision decideLearning(String message, AiIntent intent, Long userId, AiSessions session){
        List<String> ruleHits = new ArrayList<>();

        if (looksLikeLearningPlanQueryRequest(message)) {
            ruleHits.add("learning_plan_query");
            return AgentDecision.builder()
                    .action(AgentAction.TOOL)
                    .intent(intent == null ? "LEARNING_PLAN_QUERY" : intent.getIntent())
                    .toolName(TOOL_DASHBOARD)
                    .retrievalMode(RETRIEVAL_NONE)
                    .ruleHits(ruleHits)
                    .reason("学习计划查询句，使用 dashboard 读工具")
                    .build();
        }

        if (isCtaSuggestion(intent)) {
            return cta(
                    intent == null ? null : intent.getIntent(),
                    ruleHits,
                    "分类器建议 CTA，等待用户进一步澄清"
            );
        }

        AiWorkflowType workflowType = resolveLearningWorkflowType(intent, message, ruleHits);
        if (workflowType == null) {
            // LLM 想启动 Workflow，但后端确定性规则没有复核通过。
            // 这里不能直接 CHAT，否则双签失败被静默放行。
            if (isWorkflowSuggestion(intent)) {
                return cta(
                        intent == null ? null : intent.getIntent(),
                        ruleHits,
                        "LLM 建议启动 Workflow，但后端规则未命中，降级 CTA"
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

        if (!isLowRisk(intent)) {
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
                    "本会话自动拉起次数已达上限，降级 CTA"
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
                    "分类器建议 CTA，等待用户进一步澄清"
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
    private AgentDecision decideArticleAgent(AiIntent intent, PageContextDTO pageContext) {
        List<String> ruleHits = new ArrayList<>();

        if (isCtaSuggestion(intent)) {
            return cta(
                    intent.getIntent(),
                    ruleHits,
                    "分类器建议 CTA，等待用户进一步澄清"
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

        if (!hasArticleId(pageContext, intent)) {
            ruleHits.add("article_context_missing");
            return cta(
                    intent.getIntent(),
                    ruleHits,
                    "文章 Agent 缺少当前文章上下文，降级 CTA 等待澄清"
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

    private AgentDecision cta(
            String intent,
            List<String> ruleHits,
            String reason
    ) {
        return AgentDecision.builder()
                .action(AgentAction.CTA)
                .intent(intent)
                .retrievalMode(RETRIEVAL_NONE)
                .ruleHits(ruleHits)
                .reason(reason)
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

    private AiWorkflowType resolveLearningWorkflowType(AiIntent intent, String message, List<String> ruleHits) {
        if (isLearningAssistIntent(intent) && looksLikeLearningDifficultyRequest(message)) {
            ruleHits.add("learning_assist_rule");
            return AiWorkflowType.LEARNING_ASSIST;
        }
        if (isLearningProgressIntent(intent) && looksLikeLearningProgressRequest(message)) {
            ruleHits.add("learning_progress_rule");
            return AiWorkflowType.LEARNING_PROGRESS;
        }
        if (isLearningPlanIntent(intent) && looksLikeLearningPlanRequest(message)) {
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
     * 判断是否进入 Agent Runtime（LEARNING_AGENT）。
     *
     * 只认分类器主判，不做正则兜底。
     * 注意：LEARNING_AGENT 故意不放进 isLearningDomain，
     * 从而与学习 Workflow 管道隔离（见 decide() 注释）。
     */
    private boolean isLearningAgentIntent(AiIntent intent) {
        return intent != null
                && "LEARNING_AGENT".equals(intent.getIntent());
    }

    /**
     * 判断是否进入文章 Agent Runtime（ARTICLE_AGENT，V2.5）。
     *
     * 只认分类器主判，不做正则兜底。
     * 注意：ARTICLE_AGENT 故意不放进任何 Workflow 管道，
     * 与 LEARNING_AGENT 同理隔离（见 decide() 注释）。
     */
    private boolean isArticleAgentIntent(AiIntent intent) {
        return intent != null
                && "ARTICLE_AGENT".equals(intent.getIntent());
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
                    "文章优化缺少当前文章 ID，降级 CTA"
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
                    "文章动作必须在文章详情页执行"
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
                    "保存或发布必须在文章编辑器页面执行"
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

    private boolean isLearningPlanIntent(AiIntent intent) {
        return intent != null
                && ("LEARNING_PLAN".equals(intent.getIntent())
                || "LEARNING_PLAN".equals(intent.getSuggestedWorkflowType()));
    }

    private boolean isLearningProgressIntent(AiIntent intent) {
        return intent != null
                && ("LEARNING_PROGRESS".equals(intent.getIntent())
                || "LEARNING_PROGRESS".equals(intent.getSuggestedWorkflowType()));
    }

    private boolean isLearningAssistIntent(AiIntent intent) {
        return intent != null
                && ("LEARNING_ASSIST".equals(intent.getIntent())
                || "LEARNING_ASSIST".equals(intent.getSuggestedWorkflowType()));
    }

    // 入口兜底：学习意图本身（想/要/帮我 + 学/入门/进阶 + 目标对象）才起规划 Workflow。
    // 纯名词命中（例如“学习规划”）容易误伤查询句，所以这里保持旧路由的收敛规则。
    // V3.0：补「创建/制定/给我…学习计划」变体——这类明确诉求不因正则漏匹配被降级 CTA。
    // 查询句已由 looksLikeLearningPlanQueryRequest 优先排除，不会误伤。
    private boolean looksLikeLearningPlanRequest(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String text = message.trim();
        return text.matches(".*(想|要|帮我|打算).{0,10}?(学|学习|入门|进阶|掌握).{1,30}.*")
                || text.matches(".*(创建|制定|规划一下|安排一下|给我|来一个|做一个|搞一个).{0,15}(学习|学).{0,15}(计划|规划|路线).*")
                || text.matches(".*(学习|学).{0,15}(计划|规划|路线).{0,15}(创建|制定|规划一下|安排一下|给我|来一个|做一个|搞一个).*");
    }

    // 查询排除：询问/查看已有计划（查词/询问词 + 计划词，两种语序）→ 走 dashboard 读工具，不进 Workflow。
    // 查询句误进制定 Workflow 的成本更高，所以这里宁可范围稍宽。
    private boolean looksLikeLearningPlanQueryRequest(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String text = message.trim();
        return text.matches(".*(看看|查|有几个|有哪些|多少个|学到哪|进行到哪|做到哪|进度|计划是什么|都有什么).{0,12}(学习)?(计划|规划|路线|进度).*")
                || text.matches(".*(我)?(的)?(学习)?(计划|规划|路线).{0,10}(学到哪|进行到哪|做到哪|怎么样|是什么|有哪些|几个).*");
    }

    // 调整类动词 + 计划/进度/阶段/任务关键词 → 学习进度 Workflow 候选。
    private boolean looksLikeLearningProgressRequest(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String text = message.trim();
        return text.matches(".*(调整|改一下|改改|修改|更新|重新|重排|压缩|加快|延长|缩短|去掉|删掉|加点|加个|换个).{0,12}(学习)?(计划|进度|阶段|任务|安排|节奏).*")
                || text.matches(".*(调整|修改|更新|重新|压缩|加快|缩短).{0,8}(学习|学).*")
                || text.matches(".*(学习)?(计划|进度|阶段|任务|安排|节奏).{0,20}(调整|改一下|改改|修改|更新|重新|重排|压缩|加快|延长|缩短|去掉|删掉|加点|加个|换个).*");
    }

    // 难点攻坚：难度词 + 计划类名词，双向语序。
    private boolean looksLikeLearningDifficultyRequest(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String text = message.trim();
        String difficulty = "(卡住|卡壳|不会|看不懂|看不太懂|没看懂|好难|挺难|太难|很难|不理解|不明白|弄不明白|总是忘|总忘|记不住|学不会|学不明白|搞不懂)";
        String planWord = "(计划|规划|任务|阶段|进度)";
        return text.matches(".*" + planWord + ".{0,12}?" + difficulty + ".*")
                || text.matches(".*" + difficulty + ".{0,12}?" + planWord + ".*");
    }

}
