package com.hailin.blogsystem.ai.agent;

import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.exception.BusinessException;
import com.hailin.blogsystem.service.ArticlesService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 文章域写动作执行器（V3.6 / V3.7）。
 *
 * - UPDATE_ARTICLE_TITLE（V3.4）：并发防护三明治语义——proposal.articleTitle = 提案时刻 DB 权威旧标题（锚）→
 *   stale 校验 currentTitle != 锚 → 拒绝 → service 层旧值条件更新 WHERE title = 锚收口
 * - HIDE_ARTICLE / PUBLISH_ARTICLE（V3.7 可见性批次）：前置状态从动作方向推导（HIDE 前置 PUBLISHED /
 *   PUBLISH 前置 HIDDEN），proposal 不加状态锚；confirm 前置 stale 校验（明确文案层）+
 *   service 原子条件更新 WHERE status = expectedStatus 收口（Codex TOCTOU 修正：校验通过 ≠ 原子执行）
 */
@Component
@Slf4j
public class ArticleAgentWriteActionExecutor implements AgentWriteActionExecutor {

    private final ArticlesService articlesService;

    public ArticleAgentWriteActionExecutor(ArticlesService articlesService) {
        this.articlesService = articlesService;
    }

    @Override
    public boolean supports(String actionType) {
        return AgentWriteProposal.TYPE_UPDATE_ARTICLE_TITLE.equals(actionType)
                || AgentWriteProposal.TYPE_HIDE_ARTICLE.equals(actionType)
                || AgentWriteProposal.TYPE_PUBLISH_ARTICLE.equals(actionType);
    }

    @Override
    public WriteActionResult execute(AiAgentRun run, AgentWriteProposal proposal, Long userId) {
        return switch (proposal.actionType()) {
            case AgentWriteProposal.TYPE_UPDATE_ARTICLE_TITLE -> executeUpdateArticleTitle(run, proposal, userId);
            case AgentWriteProposal.TYPE_HIDE_ARTICLE -> executeVisibilityChange(run, proposal, userId,
                    BlogConstants.ArticlesStatus.PUBLISHED, BlogConstants.ArticlesStatus.HIDDEN, "隐藏");
            case AgentWriteProposal.TYPE_PUBLISH_ARTICLE -> executeVisibilityChange(run, proposal, userId,
                    BlogConstants.ArticlesStatus.HIDDEN, BlogConstants.ArticlesStatus.PUBLISHED, "公开");
            default -> throw new BusinessException(
                    BlogConstants.ErrorCode.SERVER_ERROR,
                    "提案数据异常，无法执行");
        };
    }

    private WriteActionResult executeUpdateArticleTitle(AiAgentRun run, AgentWriteProposal proposal, Long userId) {
        if (proposal.articleId() == null || proposal.articleId().isBlank()
                || proposal.articleTitle() == null || proposal.articleTitle().isBlank()
                || proposal.newTitle() == null || proposal.newTitle().isBlank()) {
            throw new BusinessException(
                    BlogConstants.ErrorCode.BAD_REQUEST,
                    "提案缺少文章信息或新标题");
        }
        Long articleId;
        try {
            articleId = Long.valueOf(proposal.articleId().trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(
                    BlogConstants.ErrorCode.BAD_REQUEST,
                    "提案文章 ID 无效");
        }

        Articles article = articlesService.getById(articleId);
        if (article == null) {
            throw new BusinessException(
                    BlogConstants.ErrorCode.NOT_FOUND,
                    "文章不存在或已删除");
        }
        if (!article.getAuthorId().equals(userId)) {
            throw new BusinessException(
                    BlogConstants.ErrorCode.FORBIDDEN,
                    "不存在或无权访问");
        }

        // stale 校验：当前标题必须仍等于提案锚（proposal.articleTitle），不等 = 提案后已被并发修改 → 拒绝不误写
        if (!article.getTitle().equals(proposal.articleTitle())) {
            throw new BusinessException(
                    BlogConstants.ErrorCode.CONFLICT,
                    "文章标题已变化，请重新发起修改");
        }
        // 同名改名边界（防御纵深：提案前已预检过，此处再拒——提案可能被篡改/旧版本生成）
        if (proposal.newTitle().trim().equalsIgnoreCase(proposal.articleTitle().trim())) {
            throw new BusinessException(
                    BlogConstants.ErrorCode.BAD_REQUEST,
                    "新标题与原标题相同");
        }

        // before 快照
        Map<String, Object> beforeArticle = articleSnapshot(article);
        articlesService.updateArticleTitle(articleId, proposal.articleTitle(), proposal.newTitle().trim(), userId);
        // after 快照
        Articles afterArticle = articlesService.getById(articleId);
        Map<String, Object> afterArticleMap = afterArticle == null ? Map.of() : articleSnapshot(afterArticle);

        String resultMessage = "已将文章《" + proposal.articleTitle() + "》的标题改为《"
                + proposal.newTitle().trim() + "》。";

        Map<String, Object> executed = new HashMap<>();
        executed.put("articleId", articleId);
        executed.put("articleTitle", proposal.articleTitle());
        if (proposal.newTitle() != null) {
            executed.put("newTitle", proposal.newTitle());
        }
        executed.put("beforeArticle", beforeArticle);
        executed.put("afterArticle", afterArticleMap);

        log.info("写动作执行完成: runId={}, {}", run.getId(), resultMessage);
        return new WriteActionResult(resultMessage, executed);
    }

    /**
     * V3.7 可见性批次共用执行（HIDE / PUBLISH 方向由参数决定）：
     * 必填校验（articleId）→ 查文章（归属）→ 前置 stale 校验（当前状态 == expectedStatus，明确文案层）→
     * before 快照 → service 原子条件更新（WHERE status = expectedStatus 收口微秒窗口）→ after 快照。
     * 标题文案用执行时刻 DB 当前标题（可见性动作与标题无关，避免用过期提案标题）。
     */
    private WriteActionResult executeVisibilityChange(AiAgentRun run, AgentWriteProposal proposal, Long userId,
                                                      Integer expectedStatus, Integer targetStatus, String actionLabel) {
        if (proposal.articleId() == null || proposal.articleId().isBlank()) {
            throw new BusinessException(
                    BlogConstants.ErrorCode.BAD_REQUEST,
                    "提案缺少文章信息");
        }
        Long articleId;
        try {
            articleId = Long.valueOf(proposal.articleId().trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(
                    BlogConstants.ErrorCode.BAD_REQUEST,
                    "提案文章 ID 无效");
        }

        Articles article = articlesService.getById(articleId);
        if (article == null) {
            throw new BusinessException(
                    BlogConstants.ErrorCode.NOT_FOUND,
                    "文章不存在或已删除");
        }
        if (!article.getAuthorId().equals(userId)) {
            throw new BusinessException(
                    BlogConstants.ErrorCode.FORBIDDEN,
                    "不存在或无权访问");
        }
        // 前置 stale 校验（明确文案层；service 条件更新是第二层收口）
        if (!Objects.equals(article.getStatus(), expectedStatus)) {
            throw new BusinessException(
                    BlogConstants.ErrorCode.CONFLICT,
                    "文章状态已变化，请重新发起" + actionLabel + "请求");
        }

        // before 快照
        Map<String, Object> beforeArticle = articleSnapshot(article);
        articlesService.updateArticleVisibility(articleId, expectedStatus, targetStatus, userId);
        // after 快照
        Articles afterArticle = articlesService.getById(articleId);
        Map<String, Object> afterArticleMap = afterArticle == null ? Map.of() : articleSnapshot(afterArticle);

        String currentTitle = article.getTitle() == null ? "" : article.getTitle();
        String resultMessage = "已将文章《" + currentTitle + "》" + actionLabel + "。";

        Map<String, Object> executed = new HashMap<>();
        executed.put("articleId", articleId);
        executed.put("beforeArticle", beforeArticle);
        executed.put("afterArticle", afterArticleMap);

        log.info("写动作执行完成: runId={}, {}", run.getId(), resultMessage);
        return new WriteActionResult(resultMessage, executed);
    }

    /** 文章行关键字段快照（before/after 留痕；content 大字段不入 context_json） */
    private Map<String, Object> articleSnapshot(Articles article) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("id", article.getId());
        snapshot.put("title", article.getTitle());
        snapshot.put("status", article.getStatus());
        snapshot.put("updatedAt", article.getUpdatedAt());
        return snapshot;
    }
}
