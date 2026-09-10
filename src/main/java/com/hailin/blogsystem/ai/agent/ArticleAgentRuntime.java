package com.hailin.blogsystem.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.mapper.AiAgentStepMapper;
import com.hailin.blogsystem.service.ArticlesService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 文章域 Agent Runtime（V2.5 第二个单 Agent）。
 *
 * 继承 AbstractAgentRuntime 公共循环骨架，本类只保留文章域差异：
 * - 白名单：QUERY_ARTICLE / QUERY_MEMORY / SEARCH_RAG + 通用终态
 * - V3.4 起 + SUGGEST_WRITE（受控写跨域第一刀 UPDATE_ARTICLE_TITLE：改自己文章标题，
 *   范围锁死文章详情页——articleId 只认页面上下文，提案端查库取权威旧标题作并发锚）
 * - 建议白名单：仅 OPTIMIZE_ARTICLE
 * - effectiveGoal：把后端权威 articleId 线索注入目标（QUERY_ARTICLE 校验以数据库为准）
 *
 * 入口：文章页 + 优化诉求（分类器 ARTICLE_AGENT → Planner → 公共 SSE 管道）。
 */
@Service
@Slf4j
public class ArticleAgentRuntime extends AbstractAgentRuntime implements AgentRuntime {

    private static final Set<AgentStepActionType> ALLOWED_ACTIONS = Set.of(
            AgentStepActionType.QUERY_ARTICLE,
            AgentStepActionType.QUERY_MEMORY,
            AgentStepActionType.SEARCH_RAG,
            AgentStepActionType.ASK_USER,
            AgentStepActionType.FINAL_ANSWER,
            AgentStepActionType.SUGGEST_WORKFLOW,
            AgentStepActionType.SUGGEST_WRITE
    );

    /**
     * V2.5：文章域 Agent 只能建议文章优化 Workflow。
     * confirm 时还会二次校验。
     */
    private static final Set<String> ALLOWED_SUGGEST_WORKFLOW_TYPES = Set.of(
            "OPTIMIZE_ARTICLE"
    );

    /** V3.11 手测修正：验证-补查链多一步预算（典型链 读+拦+补查+答=4 步，留 1 步绕圈余量）。 */
    private static final int ARTICLE_MAX_STEPS = 6;

    private final AgentStepDecider decider;
    private final ArticlesService articlesService;
    private final ArticleSessionAnchorService anchorService;
    private final ArticleEvidenceVerifier evidenceVerifier;

    public ArticleAgentRuntime(
            ArticleAgentStepDecider decider,
            ArticleAgentActionExecutor executor,
            AiAgentRunMapper runMapper,
            AiAgentStepMapper stepMapper,
            ObjectMapper objectMapper,
            ArticlesService articlesService,
            ArticleSessionAnchorService anchorService,
            ArticleEvidenceVerifier evidenceVerifier
    ) {
        super(executor, runMapper, stepMapper, objectMapper);
        this.decider = decider;
        this.articlesService = articlesService;
        this.anchorService = anchorService;
        this.evidenceVerifier = evidenceVerifier;
    }

    // ==================== 领域钩子 ====================

    /** SUGGEST_WRITE 是扩展终态：领域拒绝（零观察 / 同名预检）后循环跳过普通执行 */
    @Override
    protected boolean isTerminalExtension(AgentStepDecision decision) {
        return decision != null && decision.actionType() == AgentStepActionType.SUGGEST_WRITE;
    }

    // ==================== 领域钩子 ====================

    @Override
    protected AgentStepDecider decider() {
        return decider;
    }

    @Override
    protected Set<AgentStepActionType> allowedActions() {
        return ALLOWED_ACTIONS;
    }

    @Override
    protected Set<String> allowedSuggestWorkflowTypes() {
        return ALLOWED_SUGGEST_WORKFLOW_TYPES;
    }

    @Override
    protected String emptyAnswerFallback() {
        return "已为你分析这篇文章，但未生成具体建议。";
    }

    @Override
    protected String emptyAskUserFallback() {
        return "请补充一下你的优化诉求？";
    }

    @Override
    protected String defaultWorkflowSuggestionReason(String workflowType) {
        return "根据当前文章情况，建议进入「" + workflowType + "」流程。";
    }

    @Override
    protected String suggestWorkflowRejectHint() {
        return "系统提示：你在没有任何查询结果时尝试建议启动 Workflow，后端已拒绝。"
                + "请先执行只读查询（QUERY_ARTICLE / QUERY_MEMORY / SEARCH_RAG）再决策。";
    }

    @Override
    protected String emptySummaryFallback() {
        return "暂时无法整理这篇文章的优化建议，请补充一下你想改哪里。";
    }

    @Override
    protected String summaryHeader() {
        return "根据对这篇文章的分析，整理如下：\n";
    }

    /**
     * 文章归属/存在校验失败属于终局失败：直接结束 run，不继续循环。
     */
    @Override
    protected boolean isTerminalFailure(Throwable e) {
        return e instanceof ArticleNotOwnedException;
    }

    /** V3.11 手测修正：验证-补查链步数预算（骨架默认 5，收敛门后典型链 读+拦+补查+答 需 5-6 步）。 */
    @Override
    protected int maxSteps() {
        return ARTICLE_MAX_STEPS;
    }

    // ==================== V3.11 证据收敛门（文章域接线） ====================

    /**
     * V3.11：FINAL_ANSWER 前证据收敛门——规则闸（目标文章没读 → NEED_MORE）
     * + 语义闸（草案 vs 证据支撑性）由 ArticleEvidenceVerifier 实现。
     */
    @Override
    protected Verdict verifyBeforeFinalization(
            AiAgentRun run,
            AgentStepDecision decision,
            List<String> observations,
            List<AgentStepActionType> successfulActions
    ) {
        return evidenceVerifier.verify(run, decision, observations, successfulActions);
    }

    /**
     * V3.11 到顶保守收尾门（S1/S1b）：
     * - S1：目标文章已决议但从没成功读过 → 不给 summarize 假装分析的机会
     * - S1b：证据收敛门拦过 NEED_MORE 且从未被满足 → 不足证据不硬答
     */
    @Override
    protected boolean conservativeShutdownRequired(
            AiAgentRun run,
            List<AgentStepActionType> successfulActions,
            boolean hasUnresolvedVerifierRejection
    ) {
        boolean articleNeverRead = run.getTargetArticleId() != null
                && !(successfulActions != null
                && successfulActions.contains(AgentStepActionType.QUERY_ARTICLE));
        return articleNeverRead || hasUnresolvedVerifierRejection;
    }

    @Override
    protected String conservativeShutdownMessage() {
        return "目前收集到的信息还不足以让我给出可靠结论，我先不硬答。"
                + "你可以换个更具体的问题，或让我重新分析一次。";
    }

    /** V3.11：NEED_MORE 拦截提示（带文章域补查动作指引，观察面向 LLM 可带动作名）。 */
    @Override
    protected String verifierRejectHint(Verdict verdict) {
        return "系统提示：你的回答依据不足（缺 " + verdict.missingEvidenceLabel()
                + "）：" + (verdict.reason() == null || verdict.reason().isBlank()
                ? "证据不足以支撑该回答" : verdict.reason())
                + "。请先补充查询再回答，不要编造：需要目标文章某段/某节细节用 QUERY_ARTICLE 且 input.focus"
                + " 指明要哪一段；概念背景用 SEARCH_RAG；写作偏好用 QUERY_MEMORY。";
    }

    // ==================== V3.12 结论锚写点 A ====================

    /**
     * FINAL_ANSWER 终态写结论锚（V3.12 设计稿 §3.2 写点 A）。
     *
     * 四项条件全满足才写：
     * 1. sessionId 非空（锚是会话级状态）
     * 2. targetArticleId 非空（无目标文章 = 无「这篇文章的结论」）
     * 3. successfulActions 含 QUERY_ARTICLE（**证据边界**）
     * 4. finalAnswer 非空——由骨架保证（兜底文案不调本钩子）
     *
     * 条件 3 的说明：V3.11 的 R1 规则闸已保证「目标文章已决议未读 → 拦截」，
     * 拦截后步数耗尽走保守收尾（到不了这里），所以正常路径下它冗余。
     * **保留**是因为验证器 fail-open（挂掉即放行）时这是唯一防线，成本一行。
     *
     * fail-open：写锚失败不影响用户看到回答（此时 run 已落库，回答已输出）。
     */
    @Override
    protected void onAnswerConcluded(
            AiAgentRun run,
            String finalAnswer,
            List<AgentStepActionType> successfulActions
    ) {
        try {
            if (run.getSessionId() == null || run.getTargetArticleId() == null) {
                return;
            }
            boolean readArticle = successfulActions != null
                    && successfulActions.contains(AgentStepActionType.QUERY_ARTICLE);
            if (!readArticle) {
                return;   // 没真读过文章 → 不把无证据的回答存成结论
            }
            anchorService.markConclusion(
                    run.getSessionId(),
                    run.getTargetArticleId(),
                    finalAnswer,
                    run.getId(),
                    ArticleSessionAnchorService.CONCLUSION_SOURCE_FINAL_ANSWER
            );
        } catch (Exception e) {
            log.warn("结论锚写入失败（不影响回答）: runId={}", run.getId(), e);
        }
    }

    /**
     * V3.8：把定位候选（当前页面文章 + 会话最近讨论文章）注入决策目标。
     *
     * 候选标题取数据库权威（查库现取，杜绝快照漂）；候选缺失显式标注"无"，
     * 让决策器知道 CURRENT_PAGE / SESSION_LAST 哪个非法。候选是 LLM 唯一可信文章信息源——
     * 决策器不填 ID，只输出 anchorMode，后端按模式决议目标并查库校验归属。
     */
    @Override
    protected String effectiveGoal(String goal, Long userId, Long sessionId, PageContextDTO pageContext) {
        StringBuilder sb = new StringBuilder(goal);

        String pageArticleId = pageContext == null ? null : pageContext.getArticleId();
        if (pageArticleId != null && !pageArticleId.isBlank()) {
            String title = authoritativeTitle(pageArticleId);
            sb.append("\n【页面上下文】");
            if ("editor-edit".equals(pageContext.getPageType())) {
                // 编辑器页：正文来自数据库已保存版本，编辑器里未保存的修改不存在于任何地方。
                // 不说清楚 = AI 拿着旧正文评"这篇文章"而用户以为它看的是编辑器里的当前内容。
                sb.append("用户正在编辑器中编辑这篇文章：《")
                        .append(title == null ? "(标题未知)" : title)
                        .append("》(ID ").append(pageArticleId.trim()).append(")")
                        .append("（你读到的是已保存版本，编辑器里未保存的修改你看不到；"
                                + "基于正文给结论时必须说明这一点）");
            } else {
                sb.append("当前文章：《")
                        .append(title == null ? "(标题未知)" : title)
                        .append("》(ID ").append(pageArticleId.trim()).append(")");
            }
        } else {
            sb.append("\n【页面上下文】当前不在文章详情页（无当前文章）");
        }

        // 候选用可读语义（他人公开文章也算候选）——候选只用于让决策器知道「哪个 anchorMode 合法」，
        // 真正能不能写由写路径的归属校验兜底，不在这里提前收窄（2026-09-10 手测修正）
        Articles anchor = anchorService.resolveReadable(sessionId, userId);
        if (anchor != null) {
            sb.append("\n【会话最近讨论文章】《").append(anchor.getTitle()).append("》(ID ")
                    .append(anchor.getId()).append(")");
        } else {
            sb.append("\n【会话最近讨论文章】无");
        }

        sb.append("\n（以上候选仅作定位线索，后端会校验文章存在与可读性；"
                + "需要定位文章的动作请输出顶层 anchorMode，不要自己填文章 ID）");
        return sb.toString();
    }

    /** 查库取文章权威标题（候选注入用），不存在返回 null。 */
    private String authoritativeTitle(String articleId) {
        try {
            Articles article = articlesService.getById(Long.valueOf(articleId.trim()));
            return article == null ? null : article.getTitle();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * SUGGEST_WORKFLOW 建议携带目标文章 ID（confirm 时用于定位 + 归属校验）。
     * V3.8：优先 run 决议目标（跨页/指代场景），未决议时回退页面上下文（兼容）。
     */
    @Override
    protected String resolveSuggestionArticleId(PageContextDTO pageContext, AiAgentRun run) {
        if (run.getTargetArticleId() != null) {
            return String.valueOf(run.getTargetArticleId());
        }
        return pageContext == null ? null : pageContext.getArticleId();
    }

    /**
     * V3.8：每步决策后决议 run 定位目标。
     *
     * anchorMode（决策 JSON 顶层，并入 input）：
     * - SESSION_LAST：显式指会话最近文章 → 会话锚 resolve（失效 → null）
     * - 缺省/其他：页面有文章 → 当前页面文章；**页面无文章 → 自动回退会话锚**（唯一可信候选，
     *   容错 LLM 首步忘带 anchorMode 的首页场景；此时 Planner 放行必已校验锚存在）
     * 决议失败置 null，QUERY_ARTICLE 执行器按缺目标处理（FAILED step → 循环 LLM 收尾，不猜）。
     * run 目标一旦锁定全 run 沿用，提案端与预处理共同消费。
     */
    @Override
    protected void resolveStepTarget(AiAgentRun run, AgentStepDecision decision, PageContextDTO pageContext) {
        if (run.getTargetArticleId() != null) {
            return;
        }
        String anchorMode = text(decision.input(), "anchorMode");
        if ("SESSION_LAST".equals(anchorMode)) {
            run.setTargetArticleId(resolveSessionAnchorId(run));
            return;
        }
        String pageArticleId = pageContext == null ? null : pageContext.getArticleId();
        if (pageArticleId == null || pageArticleId.isBlank()) {
            // 页面无文章：缺省 CURRENT_PAGE 语义不成立 → 自动回退会话锚（唯一候选容错）
            run.setTargetArticleId(resolveSessionAnchorId(run));
            return;
        }
        try {
            run.setTargetArticleId(Long.valueOf(pageArticleId.trim()));
        } catch (NumberFormatException e) {
            run.setTargetArticleId(null);
        }
    }

    /**
     * 会话锚解析：**可读语义**（resolveReadable 已校验会话归属 + 公开可读或本人全状态）。
     *
     * 2026-09-10 手测修正：原先用 owned 的 resolve，他人公开文章解析不出锚 →
     * 在别人文章页说「刚刚那篇」会定位失败或错落到当前页。本站公开博客，
     * 读定位对齐 QA 侧语义（V3.9 已为 QA 拆出 resolveReadable）——读能读到，写仍卡归属。
     */
    private Long resolveSessionAnchorId(AiAgentRun run) {
        Articles anchor = anchorService.resolveReadable(run.getSessionId(), run.getUserId());
        return anchor == null ? null : anchor.getId();
    }

    /**
     * V3.8：动作执行前把决议目标并入 QUERY_ARTICLE 的 input（后端注入，非 LLM 输出）。
     * 目标缺失时不注入，执行器按原线索链处理（报缺文章 FAILED，不静默错查）。
     */
    @Override
    protected AgentStepDecision prepareStepDecision(AiAgentRun run, AgentStepDecision decision) {
        if (run.getTargetArticleId() == null
                || decision.actionType() != AgentStepActionType.QUERY_ARTICLE) {
            return decision;
        }
        Map<String, Object> merged = new HashMap<>(
                decision.input() == null ? Map.of() : decision.input());
        merged.put("articleId", String.valueOf(run.getTargetArticleId()));
        return decision.withInput(merged);
    }

    /**
     * V3.8：QUERY_ARTICLE 定位成功 → 更新会话文章锚（AGENT_RUN 写点）。
     * 失败（归属/不存在）不走此回调，锚不被污染。
     */
    @Override
    protected void onStepSucceeded(AiAgentRun run, AgentStepDecision decision, String observation) {
        if (decision.actionType() == AgentStepActionType.QUERY_ARTICLE
                && run.getTargetArticleId() != null) {
            anchorService.mark(run.getSessionId(), run.getTargetArticleId(),
                    ArticleSessionAnchorService.SOURCE_AGENT_RUN);
        }
    }

    /**
     * 文章域扩展终态：SUGGEST_WRITE（V3.4 受控写跨域第一刀）。
     * 裁判规则与学习域一致：至少 1 条观察才允许提案（没查就提案 = 拍脑袋）。
     */
    @Override
    protected AgentRunResult handleExtraTerminalAction(
            AiAgentRun run,
            AgentStepDecision decision,
            String goal,
            List<String> observations,
            AgentStepEmitter emitter,
            PageContextDTO pageContext
    ) {
        if (decision.actionType() != AgentStepActionType.SUGGEST_WRITE) {
            return null;
        }
        int nextStepNo = run.getUsedSteps() + 1;
        if (observations.isEmpty()) {
            String rejectReason = "首轮零观察写动作提案被拒绝：必须先执行至少一个只读查询";
            emitter.emit(nextStepNo, "SUGGEST_WRITE", "FAILED", "提案被拒绝：需先完成一次查询", null);
            recordRejectedStep(run, decision, nextStepNo, rejectReason);
            observations.add("系统提示：你在没有任何查询结果时尝试提案写动作，后端已拒绝。"
                    + "请先执行只读查询（QUERY_ARTICLE / QUERY_MEMORY / SEARCH_RAG）再决策。");
            run.setCurrentStep(nextStepNo);
            run.setUsedSteps(nextStepNo);
            run.setContextJson(toJson(clipContext(observations)));
            run.setUpdatedAt(LocalDateTime.now());
            runMapper.updateById(run);
            return null; // 循环继续，由下一轮决策收尾
        }
        return suggestWriteArticle(run, decision, observations, emitter, pageContext);
    }

    /**
     * SUGGEST_WRITE 终态处理（V3.4 改标题 + V3.7 可见性批次 HIDE/PUBLISH）。
     *
     * 安全模型（对齐学习域受控写）：
     * - 范围锁死文章详情页：articleId 只认页面上下文（后端权威线索），LLM input 摘录不作数
     * - actionType 归一三分支：UPDATE_ARTICLE_TITLE / HIDE_ARTICLE / PUBLISH_ARTICLE；
     *   null/blank/未知（含学习域动作）→ FAILED 终局，绝不回落到任何动作（文章域无旧模型兼容负担）
     * - 提案端查库取权威旧标题作 proposal.articleTitle（改名锚；QUERY_ARTICLE 观察的标题截 40 字不可靠）
     * - 归属/不存在 → 终局失败（ArticleNotOwnedException 语义，isTerminalFailure 兼容）
     * - 可见性前置从动作方向推导（HIDE 前置 PUBLISHED / PUBLISH 前置 HIDDEN）：DRAFT → FAILED 终局
     *   （提示去编辑器，人工闸保留）；已是目标状态 → FAILED step + observation 循环继续 LLM 告知（不弹卡）
     * 不执行任何写操作——执行由 confirmWrite 在用户确认后完成。
     */
    private AgentRunResult suggestWriteArticle(
            AiAgentRun run,
            AgentStepDecision decision,
            List<String> observations,
            AgentStepEmitter emitter,
            PageContextDTO pageContext
    ) {
        Map<String, Object> input = decision.input() == null ? Map.of() : decision.input();
        String actionType = text(input, "actionType");
        boolean rename = AgentWriteProposal.TYPE_UPDATE_ARTICLE_TITLE.equals(actionType);
        boolean hide = AgentWriteProposal.TYPE_HIDE_ARTICLE.equals(actionType);
        boolean publish = AgentWriteProposal.TYPE_PUBLISH_ARTICLE.equals(actionType);
        if (!rename && !hide && !publish) {
            emitter.emit(run.getUsedSteps() + 1, "SUGGEST_WRITE", "FAILED", "不支持的写动作类型", null);
            return markFailed(run, "Agent 写动作提案无效（不支持的 actionType：" + actionType + "）");
        }
        // 改名专属必填：新标题必须来自用户原话
        String newTitle = text(input, "newTitle");
        if (rename && (newTitle == null || newTitle.isBlank())) {
            emitter.emit(run.getUsedSteps() + 1, "SUGGEST_WRITE", "FAILED", "提案缺少新标题", null);
            return markFailed(run, "Agent 写动作提案无效（缺少 newTitle）");
        }

        // V3.8：写动作目标 = run 决议目标（anchorMode 解析产物，首步 QUERY_ARTICLE 定位后锁定）。
        // 不再直取 pageContext——跨页/指代场景（站 B 页说"刚刚那篇"）目标正确落在会话锚文章。
        Long targetId = run.getTargetArticleId();
        if (targetId == null) {
            emitter.emit(run.getUsedSteps() + 1, "SUGGEST_WRITE", "FAILED", "缺少文章上下文", null);
            return markFailed(run, "Agent 写动作提案无效（run 未决议定位目标，无法定位文章）");
        }
        Articles article = articlesService.getById(targetId);
        if (article == null || !article.getAuthorId().equals(run.getUserId())) {
            // 终局失败：文章不存在/不属于当前用户（对齐 QUERY_ARTICLE 归属语义，不继续循环）
            int nextStepNo = run.getUsedSteps() + 1;
            emitter.emit(nextStepNo, "SUGGEST_WRITE", "FAILED", "未找到这篇文章，或文章不属于你", null);
            recordRejectedStep(run, decision, nextStepNo, "文章归属校验失败（SUGGEST_WRITE）");
            throw new AgentRunTerminalException(
                    nextStepNo, "未找到这篇文章，或文章不属于你，无法继续处理。");
        }

        if (rename) {
            // 同名改名（无变化不弹卡）：FAILED step + observation → 循环继续由 LLM 告知用户（镜像学习域 RENAME precheck）
            if (newTitle.trim().equalsIgnoreCase(
                    article.getTitle() == null ? "" : article.getTitle().trim())) {
                return rejectStepContinue(run, decision, emitter, observations,
                        "新标题与原标题相同，改标题提案被拒绝",
                        "不要生成改标题提案，如用户确实要改可建议换个新标题");
            }
            AgentWriteProposal proposal = new AgentWriteProposal(
                    AgentWriteProposal.TYPE_UPDATE_ARTICLE_TITLE,
                    null, null, null, false, newTitle.trim(),
                    String.valueOf(targetId), article.getTitle()
            );
            String actionLabel = "提案将文章《" + article.getTitle() + "》标题改为《" + newTitle.trim() + "》";
            String finalAnswer = "已为你准备好将文章《" + article.getTitle() + "》标题改为《"
                    + newTitle.trim() + "》的提案，确认后执行。";
            return finishSuggestWrite(run, decision, observations, emitter, proposal, actionLabel, finalAnswer);
        }

        // V3.7 可见性分支：前置状态从动作方向推导（HIDE 前置 PUBLISHED / PUBLISH 前置 HIDDEN）
        boolean hideDirection = hide;
        String targetDesc = hideDirection ? "隐藏" : "公开";
        if (Objects.equals(article.getStatus(), BlogConstants.ArticlesStatus.DRAFT)) {
            // 草稿拒绝：Agent 不从详情页把草稿发布/隐藏，编辑器人工闸保留
            emitter.emit(run.getUsedSteps() + 1, "SUGGEST_WRITE", "FAILED", "草稿文章不能执行此操作", null);
            return markFailed(run, "这篇文章还未发布，隐藏/公开操作请在编辑器中完成。");
        }
        Integer expectedStatus = hideDirection
                ? BlogConstants.ArticlesStatus.PUBLISHED
                : BlogConstants.ArticlesStatus.HIDDEN;
        if (!Objects.equals(article.getStatus(), expectedStatus)) {
            // 已是目标状态（如已隐藏再提隐藏）→ 不弹卡，循环继续由 LLM 告知
            return rejectStepContinue(run, decision, emitter, observations,
                    "文章当前已处于" + ("隐藏".equals(targetDesc) ? "隐藏" : "公开") + "状态，" + targetDesc + "提案被拒绝",
                    "不要生成" + targetDesc + "提案，请直接告知用户当前状态");
        }

        AgentWriteProposal proposal = new AgentWriteProposal(
                hideDirection ? AgentWriteProposal.TYPE_HIDE_ARTICLE : AgentWriteProposal.TYPE_PUBLISH_ARTICLE,
                null, null, null, false, null,
                String.valueOf(targetId), article.getTitle()
        );
        String actionLabel = "提案将文章《" + article.getTitle() + "》" + (hideDirection ? "设为隐藏" : "公开");
        String finalAnswer = "已为你准备好将文章《" + article.getTitle() + "》"
                + (hideDirection ? "设为隐藏" : "公开") + "的提案，确认后执行。";
        return finishSuggestWrite(run, decision, observations, emitter, proposal, actionLabel, finalAnswer);
    }

    /** 业务拒绝（不弹卡）：FAILED step + observation → 循环继续由 LLM 收尾告知（镜像学习域 precheck 模式） */
    private AgentRunResult rejectStepContinue(AiAgentRun run, AgentStepDecision decision, AgentStepEmitter emitter,
                                              List<String> observations, String rejectReason, String advice) {
        int nextStepNo = run.getUsedSteps() + 1;
        emitter.emit(nextStepNo, "SUGGEST_WRITE", "FAILED", rejectReason, null);
        recordRejectedStep(run, decision, nextStepNo, rejectReason);
        observations.add("系统提示：" + rejectReason + "。" + advice + "。");
        run.setCurrentStep(nextStepNo);
        run.setUsedSteps(nextStepNo);
        run.setContextJson(toJson(clipContext(observations)));
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
        return null; // 循环继续，由 LLM 收尾告知用户
    }

    /** 提案成功落库（WAITING_WRITE_CONFIRM + pendingWriteAction），两分支共用 */
    private AgentRunResult finishSuggestWrite(AiAgentRun run, AgentStepDecision decision,
                                              List<String> observations, AgentStepEmitter emitter,
                                              AgentWriteProposal proposal, String actionLabel, String finalAnswer) {
        emitter.emit(run.getUsedSteps() + 1, "SUGGEST_WRITE", "SUCCESS", actionLabel,
                decision.thoughtSummary());
        recordTerminalStep(run, decision, run.getUsedSteps() + 1);
        run.setStatus(AiAgentRunStatus.WAITING_WRITE_CONFIRM.name());
        run.setFinalAnswer(finalAnswer);
        run.setContextJson(toJson(Map.of(
                "observations", clipContext(observations),
                "pendingWriteAction", proposal
        )));
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
        log.info("Agent Run 写动作提案: runId={}, actionType={}, articleId={}",
                run.getId(), proposal.actionType(), proposal.articleId());
        return AgentRunResult.of(
                run.getId(),
                AiAgentRunStatus.WAITING_WRITE_CONFIRM,
                run.getFinalAnswer(),
                run.getUsedSteps(),
                null,
                proposal
        );
    }
}
