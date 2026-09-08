package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.agent.ArticleSessionAnchorService;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.AiSessions;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.service.AiSessionService;
import com.hailin.blogsystem.service.ArticlesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话文章锚读写测试（V3.8）。
 * mark：覆盖式写点（session 存在才写；标题取数据库权威，容忍 null）。
 * resolve：锚存在 + 文章存在 + 归属本人 三条件全过才返回，否则 null（宁不猜）。
 */
class ArticleSessionAnchorServiceTests {

    private AiSessionService aiSessionService;
    private ArticlesService articlesService;
    private ArticleSessionAnchorService service;

    private static AiSessions session(Long id, Long articleId) {
        return session(id, 100L, articleId);
    }

    private static AiSessions session(Long id, Long userId, Long articleId) {
        AiSessions session = new AiSessions();
        session.setId(id);
        session.setUserId(userId);
        session.setLastArticleId(articleId);
        return session;
    }

    private static Articles article(Long id, Long authorId, String title) {
        Articles article = new Articles();
        article.setId(id);
        article.setAuthorId(authorId);
        article.setTitle(title);
        return article;
    }

    private static Articles article(Long id, Long authorId, String title, Integer status) {
        Articles article = article(id, authorId, title);
        article.setStatus(status);
        return article;
    }

    @BeforeEach
    void setUp() {
        aiSessionService = mock(AiSessionService.class);
        articlesService = mock(ArticlesService.class);
        service = new ArticleSessionAnchorService(aiSessionService, articlesService);
    }

    @Test
    void markWritesAnchorWithAuthoritativeTitle() {
        when(aiSessionService.getById(10L)).thenReturn(session(10L, null));
        when(articlesService.getById(12L)).thenReturn(article(12L, 100L, "Java 后端面试突围"));

        service.mark(10L, 12L, ArticleSessionAnchorService.SOURCE_AGENT_RUN);

        ArgumentCaptor<AiSessions> captor = ArgumentCaptor.forClass(AiSessions.class);
        verify(aiSessionService).updateById(captor.capture());
        AiSessions update = captor.getValue();
        assertThat(update.getLastArticleId()).isEqualTo(12L);
        assertThat(update.getLastArticleTitle()).isEqualTo("Java 后端面试突围");
        assertThat(update.getLastArticleSource()).isEqualTo("AGENT_RUN");
        assertThat(update.getLastArticleUpdatedAt()).isNotNull();
    }

    @Test
    void markSkipsWhenSessionMissing() {
        when(aiSessionService.getById(10L)).thenReturn(null);

        service.mark(10L, 12L, "AGENT_RUN");

        verify(aiSessionService, never()).updateById(any());
    }

    @Test
    void markSkipsOnNullArgs() {
        service.mark(null, 12L, "AGENT_RUN");
        service.mark(10L, null, "AGENT_RUN");

        verify(aiSessionService, never()).updateById(any());
    }

    @Test
    void markToleratesDeletedArticleForTitleSnapshot() {
        // 文章已删：本体 id 仍记录（resolve 会判失效），标题快照容忍 null
        when(aiSessionService.getById(10L)).thenReturn(session(10L, null));
        when(articlesService.getById(12L)).thenReturn(null);

        service.mark(10L, 12L, "PAGE_QA");

        ArgumentCaptor<AiSessions> captor = ArgumentCaptor.forClass(AiSessions.class);
        verify(aiSessionService).updateById(captor.capture());
        assertThat(captor.getValue().getLastArticleId()).isEqualTo(12L);
        assertThat(captor.getValue().getLastArticleTitle()).isNull();
    }

    @Test
    void resolveReturnsAnchorWhenOwned() {
        when(aiSessionService.getById(10L)).thenReturn(session(10L, 12L));
        when(articlesService.getById(12L)).thenReturn(article(12L, 100L, "Java 后端面试突围"));

        ArticleSessionAnchorService.ArticleAnchor anchor = service.resolve(10L, 100L);

        assertThat(anchor).isNotNull();
        assertThat(anchor.articleId()).isEqualTo(12L);
        // 标题为数据库权威现取（快照列不参与）
        assertThat(anchor.title()).isEqualTo("Java 后端面试突围");
    }

    @Test
    void resolveReturnsNullWithoutAnchor() {
        when(aiSessionService.getById(10L)).thenReturn(session(10L, null));

        assertThat(service.resolve(10L, 100L)).isNull();
        assertThat(service.resolve(null, 100L)).isNull();
        assertThat(service.resolve(10L, null)).isNull();
    }

    @Test
    void resolveReturnsNullWhenArticleGone() {
        when(aiSessionService.getById(10L)).thenReturn(session(10L, 12L));
        when(articlesService.getById(12L)).thenReturn(null);

        assertThat(service.resolve(10L, 100L)).isNull();
    }

    @Test
    void resolveReturnsNullWhenNotOwned() {
        when(aiSessionService.getById(10L)).thenReturn(session(10L, 12L));
        when(articlesService.getById(12L)).thenReturn(article(12L, 999L, "别人的文章"));

        assertThat(service.resolve(10L, 100L)).isNull();
    }

    // ===================== V3.9：resolve 补 session 归属校验 =====================

    @Test
    void resolveRejectsWhenSessionBelongsToAnotherUser() {
        // 会话属于 200、文章属于 100：只看文章归属会误放行，补校验后必须 null
        when(aiSessionService.getById(10L)).thenReturn(session(10L, 200L, 12L));
        when(articlesService.getById(12L)).thenReturn(article(12L, 100L, "Java 后端面试突围"));

        assertThat(service.resolve(10L, 100L)).isNull();
    }

    // ===================== V3.9：loadReadable 读权限单点 =====================

    @Test
    void loadReadableReturnsPublishedForAnyone() {
        when(articlesService.getById(12L)).thenReturn(
                article(12L, 999L, "他人的公开文章", BlogConstants.ArticlesStatus.PUBLISHED));

        assertThat(service.loadReadable(12L, 100L)).isNotNull();
    }

    @Test
    void loadReadableReturnsOwnAnyStatus() {
        when(articlesService.getById(12L)).thenReturn(
                article(12L, 100L, "自己的隐藏文章", BlogConstants.ArticlesStatus.HIDDEN));

        assertThat(service.loadReadable(12L, 100L)).isNotNull();
    }

    @Test
    void loadReadableRejectsOthersNonPublished() {
        when(articlesService.getById(12L)).thenReturn(
                article(12L, 999L, "他人的隐藏文章", BlogConstants.ArticlesStatus.HIDDEN));

        assertThat(service.loadReadable(12L, 100L)).isNull();
    }

    @Test
    void loadReadableGuestOnlyPublished() {
        when(articlesService.getById(12L)).thenReturn(
                article(12L, 100L, "自己的草稿", BlogConstants.ArticlesStatus.DRAFT));
        when(articlesService.getById(13L)).thenReturn(
                article(13L, 100L, "公开文章", BlogConstants.ArticlesStatus.PUBLISHED));

        // userId null（游客）→ 只读公开：本人草稿不可读、公开可读
        assertThat(service.loadReadable(12L, null)).isNull();
        assertThat(service.loadReadable(13L, null)).isNotNull();
    }

    @Test
    void loadReadableRejectsGoneOrNullId() {
        when(articlesService.getById(12L)).thenReturn(null);

        assertThat(service.loadReadable(12L, 100L)).isNull();
        assertThat(service.loadReadable(null, 100L)).isNull();
    }

    // ===================== V3.9：resolveReadable =====================

    @Test
    void resolveReadableReturnsOthersPublishedAnchor() {
        when(aiSessionService.getById(10L)).thenReturn(session(10L, 12L));
        when(articlesService.getById(12L)).thenReturn(
                article(12L, 999L, "他人的公开文章", BlogConstants.ArticlesStatus.PUBLISHED));

        Articles result = service.resolveReadable(10L, 100L);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(12L);
    }

    @Test
    void resolveReadableReturnsOwnHiddenAnchor() {
        when(aiSessionService.getById(10L)).thenReturn(session(10L, 12L));
        when(articlesService.getById(12L)).thenReturn(
                article(12L, 100L, "自己的隐藏文章", BlogConstants.ArticlesStatus.HIDDEN));

        assertThat(service.resolveReadable(10L, 100L)).isNotNull();
    }

    @Test
    void resolveReadableRejectsOthersHiddenAnchor() {
        when(aiSessionService.getById(10L)).thenReturn(session(10L, 12L));
        when(articlesService.getById(12L)).thenReturn(
                article(12L, 999L, "他人的隐藏文章", BlogConstants.ArticlesStatus.HIDDEN));

        assertThat(service.resolveReadable(10L, 100L)).isNull();
    }

    @Test
    void resolveReadableRejectsWhenSessionNotOwnedOrArgsNull() {
        when(aiSessionService.getById(10L)).thenReturn(session(10L, 200L, 12L));
        when(articlesService.getById(12L)).thenReturn(
                article(12L, 100L, "公开文章", BlogConstants.ArticlesStatus.PUBLISHED));

        // 会话不属当前用户：即使文章公开也不该参与本用户决议
        assertThat(service.resolveReadable(10L, 100L)).isNull();
        assertThat(service.resolveReadable(null, 100L)).isNull();
        assertThat(service.resolveReadable(10L, null)).isNull();
    }

    @Test
    void resolveReadableNullWithoutAnchorOrArticleGone() {
        when(aiSessionService.getById(10L)).thenReturn(session(10L, null));
        assertThat(service.resolveReadable(10L, 100L)).isNull();

        when(aiSessionService.getById(11L)).thenReturn(session(11L, 12L));
        when(articlesService.getById(12L)).thenReturn(null);
        assertThat(service.resolveReadable(11L, 100L)).isNull();
    }
}
