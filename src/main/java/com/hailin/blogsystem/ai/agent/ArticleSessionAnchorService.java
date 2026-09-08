package com.hailin.blogsystem.ai.agent;

import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.AiSessions;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.service.AiSessionService;
import com.hailin.blogsystem.service.ArticlesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 会话文章锚（V3.8）：ai_sessions.last_article_* 单点状态的读写。
 *
 * 写点（受控，两类事件，闲聊/纯浏览不写）：
 * - 文章域 Agent 完成一次定位（source=AGENT_RUN）
 * - 明确围绕当前文章的页面意图（source=PAGE_QA / PAGE_ACTION）
 *
 * 定位以 last_article_id 为本体；last_article_title 只是快照（候选注入参考），
 * 权威标题 resolve 时查库现取——改名不漂。
 *
 * V3.9 读语义按域拆分（锚存储通用——写点不验归属，锚里可含他人公开文章）：
 * - resolve（owned）：存在 + 归属本人。文章域 Agent / 写动作用（只能动自己的文章）
 * - resolveReadable（readable）：存在 + 公开可读或本人全状态。QA 问答用（他人公开文章也能续问）
 * - loadReadable：读权限 SQL/过滤单点，两个 resolve 与 QA 决议共用同一条口径
 * 锚失效（不满足条件）返回 null，宁可不猜。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ArticleSessionAnchorService {

    /** 锚读取结果：文章 ID（本体）+ 数据库权威标题。 */
    public record ArticleAnchor(Long articleId, String title) {
    }

    public static final String SOURCE_AGENT_RUN = "AGENT_RUN";
    public static final String SOURCE_PAGE_QA = "PAGE_QA";
    public static final String SOURCE_PAGE_ACTION = "PAGE_ACTION";

    private final AiSessionService aiSessionService;
    private final ArticlesService articlesService;

    /**
     * 写点：覆盖式更新会话文章锚。标题取数据库权威，文章已删时容忍 null（本体 id 仍可定位，resolve 会判失效）。
     */
    public void mark(Long sessionId, Long articleId, String source) {
        if (sessionId == null || articleId == null || source == null) {
            return;
        }
        AiSessions session = aiSessionService.getById(sessionId);
        if (session == null) {
            return;
        }
        Articles article = articlesService.getById(articleId);

        AiSessions update = new AiSessions();
        update.setId(sessionId);
        update.setLastArticleId(articleId);
        update.setLastArticleTitle(article == null ? null : article.getTitle());
        update.setLastArticleSource(source);
        update.setLastArticleUpdatedAt(LocalDateTime.now());
        aiSessionService.updateById(update);
        log.debug("会话文章锚更新: sessionId={}, articleId={}, source={}", sessionId, articleId, source);
    }

    /**
     * 读点（owned，文章域 Agent 用）：返回有效锚（会话存在 + 归属当前用户 + 有锚 + 文章存在 +
     * 作者 = 当前用户），任一不满足返回 null。标题为查库权威现取。
     *
     * V3.9：补 session.userId == userId 校验（锚定位入口防御，不依赖调用链纪律）。
     */
    public ArticleAnchor resolve(Long sessionId, Long userId) {
        if (sessionId == null || userId == null) {
            return null;
        }
        AiSessions session = aiSessionService.getById(sessionId);
        if (session == null || session.getUserId() == null
                || !session.getUserId().equals(userId)) {
            return null;
        }
        if (session.getLastArticleId() == null) {
            return null;
        }
        Articles article = articlesService.getById(session.getLastArticleId());
        if (article == null || !userId.equals(article.getAuthorId())) {
            return null;
        }
        return new ArticleAnchor(article.getId(), article.getTitle());
    }

    /**
     * 读权限单点（V3.9）：公开可读 + 本人全状态可读（对齐文章域 Agent「归属是唯一门槛」）。
     * 他人非公开文章一律不可读（防隐私洞）。userId 为 null（游客）→ 只读公开。
     * 返回实体（含正文，QA 决议/加载合一用）或 null。
     */
    public Articles loadReadable(Long articleId, Long userId) {
        if (articleId == null) {
            return null;
        }
        Articles article = articlesService.getById(articleId);
        if (article == null) {
            return null;
        }
        if (article.getStatus() != null
                && article.getStatus() == BlogConstants.ArticlesStatus.PUBLISHED) {
            return article;
        }
        if (userId != null && userId.equals(article.getAuthorId())) {
            return article;
        }
        return null;
    }

    /**
     * 读点（readable，QA 问答用）：返回有效锚文章实体（会话存在 + 归属当前用户 + 有锚 +
     * loadReadable 可读），任一不满足返回 null。与 owned resolve 的区别：他人公开文章也命中。
     */
    public Articles resolveReadable(Long sessionId, Long userId) {
        if (sessionId == null || userId == null) {
            return null;
        }
        AiSessions session = aiSessionService.getById(sessionId);
        if (session == null || session.getUserId() == null
                || !session.getUserId().equals(userId)) {
            return null;
        }
        if (session.getLastArticleId() == null) {
            return null;
        }
        return loadReadable(session.getLastArticleId(), userId);
    }
}
