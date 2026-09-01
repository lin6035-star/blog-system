package com.hailin.blogsystem.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.mapper.AiAgentStepMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 文章域 Agent Runtime（V2.5 第二个单 Agent）。
 *
 * 继承 AbstractAgentRuntime 公共循环骨架，本类只保留文章域差异：
 * - 白名单：QUERY_ARTICLE / QUERY_MEMORY / SEARCH_RAG + 通用终态（无 SUGGEST_WRITE，文章域禁写提案）
 * - 建议白名单：仅 OPTIMIZE_ARTICLE
 * - effectiveGoal：把后端权威 articleId 线索注入目标（QUERY_ARTICLE 校验以数据库为准）
 *
 * 入口：文章页 + 模糊优化诉求（分类器 ARTICLE_AGENT → Planner → 公共 SSE 管道）。
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
            AgentStepActionType.SUGGEST_WORKFLOW
    );

    /**
     * V2.5：文章域 Agent 只能建议文章优化 Workflow。
     * confirm 时还会二次校验。
     */
    private static final Set<String> ALLOWED_SUGGEST_WORKFLOW_TYPES = Set.of(
            "OPTIMIZE_ARTICLE"
    );

    private final AgentStepDecider decider;

    public ArticleAgentRuntime(
            ArticleAgentStepDecider decider,
            ArticleAgentActionExecutor executor,
            AiAgentRunMapper runMapper,
            AiAgentStepMapper stepMapper,
            ObjectMapper objectMapper
    ) {
        super(executor, runMapper, stepMapper, objectMapper);
        this.decider = decider;
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
}
