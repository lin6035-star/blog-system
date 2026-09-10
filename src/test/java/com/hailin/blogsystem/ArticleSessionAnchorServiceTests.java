package com.hailin.blogsystem;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
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
    private BaseMapper<AiSessions> baseMapper;
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
        // markConclusion 走 getBaseMapper().update(...)（非 lambda wrapper：mock 环境无 TableInfo 缓存）
        baseMapper = mock(BaseMapper.class);
        when(aiSessionService.getBaseMapper()).thenReturn(baseMapper);
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

    // ==================== V3.12 结论锚 ====================

    private static AiSessions conclusionSession(
            Long id, Long userId, Long articleId, String text, Long sourceRunId) {
        AiSessions session = new AiSessions();
        session.setId(id);
        session.setUserId(userId);
        session.setLastConclusionArticleId(articleId);
        session.setLastConclusionText(text);
        session.setLastConclusionSourceRunId(sourceRunId);
        session.setLastConclusionSourceType(
                ArticleSessionAnchorService.CONCLUSION_SOURCE_FINAL_ANSWER);
        return session;
    }

    @Test
    void markConclusionWritesWhenNewerRun() {
        when(aiSessionService.getById(10L)).thenReturn(session(10L, null));
        when(baseMapper.update(any(), any())).thenReturn(1);

        service.markConclusion(10L, 12L, "建议补充缓存击穿原理",
                200L, ArticleSessionAnchorService.CONCLUSION_SOURCE_FINAL_ANSWER);

        verify(baseMapper).update(any(), any());
    }

    @Test
    void markConclusionAbandonedWhenOlderRunLosesCas() {
        // 并发保护：新 run 的雪花 ID 不大于锚里已有 sourceRunId → CAS 拒绝（影响行数 0）
        // 场景：较早创建的 run 较晚结束，不能覆盖较新的结论
        when(aiSessionService.getById(10L)).thenReturn(session(10L, null));
        when(baseMapper.update(any(), any())).thenReturn(0);

        service.markConclusion(10L, 12L, "旧结论", 100L,
                ArticleSessionAnchorService.CONCLUSION_SOURCE_FINAL_ANSWER);

        // 写入被拒是正常分支，不抛异常（调用方无法感知，也不应感知）
        verify(baseMapper).update(any(), any());
    }

    @Test
    void markConclusionSkippedOnBlankTextOrNullArgs() {
        service.markConclusion(null, 12L, "x", 1L, "T");
        service.markConclusion(10L, null, "x", 1L, "T");
        service.markConclusion(10L, 12L, "  ", 1L, "T");
        service.markConclusion(10L, 12L, "x", null, "T");
        service.markConclusion(10L, 12L, "x", 1L, null);

        verify(baseMapper, never()).update(any(), any());
    }

    @Test
    void resolveConclusionReturnsAnchorWhenArticleMatches() {
        when(aiSessionService.getById(10L)).thenReturn(
                conclusionSession(10L, 100L, 12L, "建议补充缓存击穿原理", 200L));
        when(articlesService.getById(12L)).thenReturn(
                article(12L, 100L, "Redis 缓存设计", BlogConstants.ArticlesStatus.PUBLISHED));

        ArticleSessionAnchorService.ConclusionAnchor anchor =
                service.resolveConclusion(10L, 100L, 12L);

        assertThat(anchor).isNotNull();
        assertThat(anchor.articleId()).isEqualTo(12L);
        assertThat(anchor.text()).isEqualTo("建议补充缓存击穿原理");
        assertThat(anchor.sourceRunId()).isEqualTo(200L);
    }

    @Test
    void resolveConclusionNullWhenArticleDiffers() {
        // 串文章防线：上轮聊 A（12），本轮优化 B（99）→ 不注入
        when(aiSessionService.getById(10L)).thenReturn(
                conclusionSession(10L, 100L, 12L, "建议补充缓存击穿原理", 200L));

        assertThat(service.resolveConclusion(10L, 100L, 99L)).isNull();
    }

    @Test
    void resolveConclusionNullWhenSessionNotOwned() {
        // 越权防线：读他人会话的结论锚
        when(aiSessionService.getById(10L)).thenReturn(
                conclusionSession(10L, 200L, 12L, "建议", 200L));

        assertThat(service.resolveConclusion(10L, 100L, 12L)).isNull();
    }

    @Test
    void resolveConclusionReadsOthersPublishedArticle() {
        // V3.12 读权限对齐：他人公开文章的分析结论也能继承
        // （写路径归属校验不受影响——别人的文章本来就创建不了 OPTIMIZE Workflow）
        when(aiSessionService.getById(10L)).thenReturn(
                conclusionSession(10L, 100L, 12L, "建议补充示例", 200L));
        when(articlesService.getById(12L)).thenReturn(
                article(12L, 999L, "别人的公开文章", BlogConstants.ArticlesStatus.PUBLISHED));

        assertThat(service.resolveConclusion(10L, 100L, 12L)).isNotNull();
    }

    @Test
    void resolveConclusionNullWhenOthersArticleNotPublished() {
        // 隐私洞防线：他人非公开文章不继承（即使锚存在）
        when(aiSessionService.getById(10L)).thenReturn(
                conclusionSession(10L, 100L, 12L, "建议", 200L));
        when(articlesService.getById(12L)).thenReturn(
                article(12L, 999L, "别人的草稿", BlogConstants.ArticlesStatus.DRAFT));

        assertThat(service.resolveConclusion(10L, 100L, 12L)).isNull();
    }

    @Test
    void resolveConclusionTruncatesLongTextToInjectMax() {
        // finalAnswer 常上千字：注入侧截断 500 字（防「参考」压过「用户优化要求」）
        String longText = "建".repeat(1500);
        when(aiSessionService.getById(10L)).thenReturn(
                conclusionSession(10L, 100L, 12L, longText, 200L));
        when(articlesService.getById(12L)).thenReturn(
                article(12L, 100L, "文章", BlogConstants.ArticlesStatus.PUBLISHED));

        ArticleSessionAnchorService.ConclusionAnchor anchor =
                service.resolveConclusion(10L, 100L, 12L);

        assertThat(anchor.text()).hasSize(ArticleSessionAnchorService.CONCLUSION_INJECT_MAX);
    }

    @Test
    void resolveConclusionNullOnBlankTextOrNullTarget() {
        when(aiSessionService.getById(10L)).thenReturn(
                conclusionSession(10L, 100L, 12L, "  ", 200L));
        assertThat(service.resolveConclusion(10L, 100L, 12L)).isNull();
        assertThat(service.resolveConclusion(10L, 100L, null)).isNull();
        assertThat(service.resolveConclusion(null, 100L, 12L)).isNull();
    }
}
