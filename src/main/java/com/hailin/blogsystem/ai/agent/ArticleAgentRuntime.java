package com.hailin.blogsystem.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;
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

    private final AgentStepDecider decider;
    private final ArticlesService articlesService;

    public ArticleAgentRuntime(
            ArticleAgentStepDecider decider,
            ArticleAgentActionExecutor executor,
            AiAgentRunMapper runMapper,
            AiAgentStepMapper stepMapper,
            ObjectMapper objectMapper,
            ArticlesService articlesService
    ) {
        super(executor, runMapper, stepMapper, objectMapper);
        this.decider = decider;
        this.articlesService = articlesService;
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

    /**
     * 把后端权威 articleId 线索注入决策目标（仅当页面上下文有文章 ID）。
     * LLM 在 QUERY_ARTICLE.input.articleId 中摘录它，但归属最终由执行器查库校验。
     */
    @Override
    protected String effectiveGoal(String goal, PageContextDTO pageContext) {
        if (pageContext == null || pageContext.getArticleId() == null
                || pageContext.getArticleId().isBlank()) {
            return goal;
        }
        return goal + "\n【页面上下文】当前文章 ID：" + pageContext.getArticleId()
                + "（仅作线索，后端会校验文章归属，请勿猜测其他文章）";
    }

    /**
     * SUGGEST_WORKFLOW 建议携带目标文章 ID（confirm 时用于定位 + 归属校验）。
     */
    @Override
    protected String resolveSuggestionArticleId(PageContextDTO pageContext) {
        return pageContext == null ? null : pageContext.getArticleId();
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
            emitter.emit(nextStepNo, "SUGGEST_WRITE", "FAILED", "提案被拒绝：需先完成一次查询");
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
        return suggestWriteArticleTitle(run, decision, observations, emitter, pageContext);
    }

    /**
     * SUGGEST_WRITE 终态处理（V3.4 UPDATE_ARTICLE_TITLE：改自己文章标题）。
     *
     * 安全模型（对齐学习域受控写）：
     * - 范围锁死文章详情页：articleId 只认页面上下文（后端权威线索），LLM input 摘录不作数
     * - actionType 归一：文章域只认 UPDATE_ARTICLE_TITLE；null/blank/未知（含学习域动作）→ FAILED 终局，
     *   绝不回落到任何动作（文章域无旧模型兼容负担，回落 = 吞掉模型幻觉）
     * - 提案端查库取权威旧标题作 proposal.articleTitle（并发防护锚；QUERY_ARTICLE 观察的标题截 40 字不可靠）
     * - 归属/不存在 → 终局失败（ArticleNotOwnedException 语义，isTerminalFailure 兼容）
     * - 同名改名（无变化）→ FAILED step + observation，循环继续由 LLM 告知（不弹卡）
     * 不执行任何写操作——执行由 confirmWrite 在用户确认后完成。
     */
    private AgentRunResult suggestWriteArticleTitle(
            AiAgentRun run,
            AgentStepDecision decision,
            List<String> observations,
            AgentStepEmitter emitter,
            PageContextDTO pageContext
    ) {
        Map<String, Object> input = decision.input() == null ? Map.of() : decision.input();
        String actionType = text(input, "actionType");
        if (!AgentWriteProposal.TYPE_UPDATE_ARTICLE_TITLE.equals(actionType)) {
            emitter.emit(run.getUsedSteps() + 1, "SUGGEST_WRITE", "FAILED", "不支持的写动作类型");
            return markFailed(run, "Agent 写动作提案无效（不支持的 actionType：" + actionType + "）");
        }
        String newTitle = text(input, "newTitle");
        if (newTitle == null || newTitle.isBlank()) {
            emitter.emit(run.getUsedSteps() + 1, "SUGGEST_WRITE", "FAILED", "提案缺少新标题");
            return markFailed(run, "Agent 写动作提案无效（缺少 newTitle）");
        }

        // 文章域写动作只发生在文章详情页：articleId 必须来自页面上下文
        String articleId = pageContext == null ? null : pageContext.getArticleId();
        if (articleId == null || articleId.isBlank()) {
            emitter.emit(run.getUsedSteps() + 1, "SUGGEST_WRITE", "FAILED", "缺少当前文章上下文");
            return markFailed(run, "Agent 写动作提案无效（缺少当前文章上下文，无法定位文章）");
        }
        Long parsedId;
        try {
            parsedId = Long.valueOf(articleId.trim());
        } catch (NumberFormatException e) {
            emitter.emit(run.getUsedSteps() + 1, "SUGGEST_WRITE", "FAILED", "当前文章 ID 无效");
            return markFailed(run, "Agent 写动作提案无效（页面上下文文章 ID 非法）");
        }
        Articles article = articlesService.getById(parsedId);
        if (article == null || !article.getAuthorId().equals(run.getUserId())) {
            // 终局失败：文章不存在/不属于当前用户（对齐 QUERY_ARTICLE 归属语义，不继续循环）
            int nextStepNo = run.getUsedSteps() + 1;
            emitter.emit(nextStepNo, "SUGGEST_WRITE", "FAILED", "未找到这篇文章，或文章不属于你");
            recordRejectedStep(run, decision, nextStepNo, "文章归属校验失败（SUGGEST_WRITE）");
            throw new AgentRunTerminalException(
                    nextStepNo, "未找到这篇文章，或文章不属于你，无法为你修改标题。");
        }

        // 同名改名（无变化不弹卡）：FAILED step + observation → 循环继续由 LLM 告知用户（镜像学习域 RENAME precheck）
        if (newTitle.trim().equalsIgnoreCase(
                article.getTitle() == null ? "" : article.getTitle().trim())) {
            int nextStepNo = run.getUsedSteps() + 1;
            String rejectReason = "新标题与原标题相同，改标题提案被拒绝";
            emitter.emit(nextStepNo, "SUGGEST_WRITE", "FAILED", rejectReason);
            recordRejectedStep(run, decision, nextStepNo, rejectReason);
            observations.add("系统提示：" + rejectReason
                    + "。请直接告知用户（不要生成改标题提案，如用户确实要改可建议换个新标题）。");
            run.setCurrentStep(nextStepNo);
            run.setUsedSteps(nextStepNo);
            run.setContextJson(toJson(clipContext(observations)));
            run.setUpdatedAt(LocalDateTime.now());
            runMapper.updateById(run);
            return null; // 循环继续，由 LLM 收尾告知用户
        }

        AgentWriteProposal proposal = new AgentWriteProposal(
                AgentWriteProposal.TYPE_UPDATE_ARTICLE_TITLE,
                null,
                null,
                null,
                false,
                newTitle.trim(),
                String.valueOf(parsedId),
                article.getTitle()
        );

        String actionLabel = "提案将文章《" + article.getTitle() + "》标题改为《" + newTitle.trim() + "》";
        String finalAnswer = "已为你准备好将文章《" + article.getTitle() + "》标题改为《"
                + newTitle.trim() + "》的提案，确认后执行。";

        emitter.emit(run.getUsedSteps() + 1, "SUGGEST_WRITE", "SUCCESS", actionLabel);
        recordTerminalStep(run, decision, run.getUsedSteps() + 1);
        run.setStatus(AiAgentRunStatus.WAITING_WRITE_CONFIRM.name());
        run.setFinalAnswer(finalAnswer);
        run.setContextJson(toJson(Map.of(
                "observations", clipContext(observations),
                "pendingWriteAction", proposal
        )));
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(run);
        log.info("Agent Run 写动作提案: runId={}, actionType={}, articleId={}, newTitle={}",
                run.getId(), proposal.actionType(), parsedId, newTitle.trim());
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
