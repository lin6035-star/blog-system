package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.rag.ArticleRagSyncService;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.service.ArticlesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * ArticlesServiceImpl.updateArticleVisibility（V3.7 Agent 受控写 HIDE_ARTICLE / PUBLISH_ARTICLE）行为测试：
 * 前置状态进 WHERE 的原子条件更新（TOCTOU 收口）+ 按 targetStatus 的缓存/RAG 副作用。
 * 种子：article 1 = user 100 已发布『Published Article』。
 */
@SpringBootTest
class ArticlesServiceUpdateVisibilityTests {

    private static final Long AUTHOR_ID = 100L;
    private static final Long OTHER_USER_ID = 101L;

    @Autowired
    private ArticlesService articlesService;

    @MockBean
    private ArticleRagSyncService articleRagSyncService;

    /** mock 掉真 ES vectorStore（对齐 ArticleRagIndexServiceTests，context 加载不依赖本地 ES） */
    @MockBean
    private VectorStore vectorStore;

    @BeforeEach
    void resetVisibility() {
        Articles article = articlesService.getById(1L);
        article.setStatus(BlogConstants.ArticlesStatus.PUBLISHED);
        article.setPublishedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        articlesService.updateById(article);
    }

    @Test
    void hideOwnPublishedArticleUpdatesStatusAndDeletesRagIndex() {
        articlesService.updateArticleVisibility(
                1L, BlogConstants.ArticlesStatus.PUBLISHED, BlogConstants.ArticlesStatus.HIDDEN, AUTHOR_ID);

        assertThat(articlesService.getById(1L).getStatus())
                .isEqualTo(BlogConstants.ArticlesStatus.HIDDEN);
        // 隐藏 → 删 RAG 索引（对齐 hideArticle）
        verify(articleRagSyncService).deleteArticleIndex(1L);
        verify(articleRagSyncService, never()).indexArticle(anyLong());
    }

    @Test
    void publishOwnHiddenArticleUpdatesStatusAndIndexesRag() {
        // 先把文章置为隐藏（模拟前置状态），再测公开
        articlesService.updateArticleVisibility(
                1L, BlogConstants.ArticlesStatus.PUBLISHED, BlogConstants.ArticlesStatus.HIDDEN, AUTHOR_ID);

        articlesService.updateArticleVisibility(
                1L, BlogConstants.ArticlesStatus.HIDDEN, BlogConstants.ArticlesStatus.PUBLISHED, AUTHOR_ID);

        Articles after = articlesService.getById(1L);
        assertThat(after.getStatus()).isEqualTo(BlogConstants.ArticlesStatus.PUBLISHED);
        // 公开 → 建 RAG 索引 + publishedAt 刷新（对齐 publishArticle 全语义）
        assertThat(after.getPublishedAt()).isNotNull();
        verify(articleRagSyncService).indexArticle(1L);
    }

    @Test
    void visibilityChangeRejectsWhenExpectedStatusMismatch() {
        // 前置状态失配（如提案后文章被并发改状态）→ 拒绝不覆盖
        assertThatThrownBy(() -> articlesService.updateArticleVisibility(
                1L, BlogConstants.ArticlesStatus.HIDDEN, BlogConstants.ArticlesStatus.PUBLISHED, AUTHOR_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文章状态已变化");

        assertThat(articlesService.getById(1L).getStatus())
                .isEqualTo(BlogConstants.ArticlesStatus.PUBLISHED);
        verify(articleRagSyncService, never()).indexArticle(anyLong());
        verify(articleRagSyncService, never()).deleteArticleIndex(anyLong());
    }

    @Test
    void visibilityChangeRejectsOtherUsersArticle() {
        assertThatThrownBy(() -> articlesService.updateArticleVisibility(
                1L, BlogConstants.ArticlesStatus.PUBLISHED, BlogConstants.ArticlesStatus.HIDDEN, OTHER_USER_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无权操作该文章");

        assertThat(articlesService.getById(1L).getStatus())
                .isEqualTo(BlogConstants.ArticlesStatus.PUBLISHED);
        verify(articleRagSyncService, never()).indexArticle(anyLong());
        verify(articleRagSyncService, never()).deleteArticleIndex(anyLong());
    }
}
