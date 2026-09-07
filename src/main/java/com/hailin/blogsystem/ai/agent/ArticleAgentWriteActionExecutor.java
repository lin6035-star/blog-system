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

/**
 * 文章域写动作执行器（V3.6，从 AgentWriteActionService 迁入 V3.4 的 UPDATE_ARTICLE_TITLE）。
 *
 * 并发防护三明治语义零变化：proposal.articleTitle = 提案时刻 DB 权威旧标题（锚）→
 * stale 校验 currentTitle != 锚 → 拒绝 → service 层旧值条件更新 WHERE title = 锚收口。
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
        return AgentWriteProposal.TYPE_UPDATE_ARTICLE_TITLE.equals(actionType);
    }

    @Override
    public WriteActionResult execute(AiAgentRun run, AgentWriteProposal proposal, Long userId) {
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
