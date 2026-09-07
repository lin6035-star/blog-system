package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.rag.ArticleRagSyncService;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.service.ArticlesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * ArticlesServiceImpl.updateArticleTitle（V3.4 Agent 受控写 UPDATE_ARTICLE_TITLE）行为测试：
 * 旧值条件更新（expectedOldTitle 锚）→ 归属校验 / 标题并发已变拒绝 / 副作用（缓存清理 + 已发布 RAG 刷新）。
 * 种子：article 1 = user 100 已发布『Published Article』；article 2 = user 100 草稿『Draft Article』。
 */
@SpringBootTest
class ArticlesServiceUpdateTitleTests {

    private static final Long AUTHOR_ID = 100L;
    private static final Long OTHER_USER_ID = 101L;

    @Autowired
    private ArticlesService articlesService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private ArticleRagSyncService articleRagSyncService;

    /** mock 掉真 ES vectorStore（对齐 ArticleRagIndexServiceTests），context 加载不依赖本地 ES */
    @MockBean
    private VectorStore vectorStore;

    @BeforeEach
    void resetTitles() {
        restoreTitle(1L, "Published Article");
        restoreTitle(2L, "Draft Article");
    }

    private void restoreTitle(Long id, String title) {
        Articles article = articlesService.getById(id);
        article.setTitle(title);
        article.setUpdatedAt(LocalDateTime.now());
        articlesService.updateById(article);
    }

    @Test
    void renameOwnPublishedArticleUpdatesTitleAndRefreshesRagAndClearsCache() {
        seedDetailCache(1L);

        articlesService.updateArticleTitle(1L, "Published Article", "Renamed Title", AUTHOR_ID);

        assertThat(articlesService.getById(1L).getTitle()).isEqualTo("Renamed Title");
        // 已发布 → 重刷 RAG doc（doc 含标题）
        verify(articleRagSyncService).indexArticle(1L);
        // 详情缓存已清理（Redis 不可用时跳过断言——业务侧删除自带 try-catch 兜底）
        assertDetailCacheCleared(1L);
    }

    @Test
    void renameOwnDraftKeepsTitleWithoutTouchingRagIndex() {
        articlesService.updateArticleTitle(2L, "Draft Article", "Draft Renamed", AUTHOR_ID);

        assertThat(articlesService.getById(2L).getTitle()).isEqualTo("Draft Renamed");
        // 草稿非 PUBLISHED → 不刷 RAG 索引（deleteArticleIndex 幂等兜底，不在此断言）
        verify(articleRagSyncService, never()).indexArticle(anyLong());
    }

    @Test
    void renameRejectsOtherUsersArticle() {
        assertThatThrownBy(() ->
                articlesService.updateArticleTitle(1L, "Published Article", "Stolen Title", OTHER_USER_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无权操作该文章");

        assertThat(articlesService.getById(1L).getTitle()).isEqualTo("Published Article");
        verify(articleRagSyncService, never()).indexArticle(anyLong());
    }

    @Test
    void renameRejectsWhenTitleChangedAfterProposalAnchor() {
        // expectedOldTitle 是提案锚（proposal.articleTitle），与当前 DB 标题失配 = 提案后被并发修改 → 拒绝不覆盖
        assertThatThrownBy(() ->
                articlesService.updateArticleTitle(1L, "Outdated Anchor Title", "Renamed Title", AUTHOR_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文章标题已变化");

        assertThat(articlesService.getById(1L).getTitle()).isEqualTo("Published Article");
        verify(articleRagSyncService, never()).indexArticle(anyLong());
    }

    private void seedDetailCache(Long articleId) {
        try {
            stringRedisTemplate.opsForValue().set(
                    RedisConstants.ARTICLE_DETAIL_KEY_PREFIX + articleId,
                    "{\"id\":" + articleId + ",\"title\":\"Published Article\"}");
        } catch (Exception e) {
            // Redis 不可用：业务走 DB 兜底，缓存断言随之跳过
        }
    }

    private void assertDetailCacheCleared(Long articleId) {
        try {
            String cached = stringRedisTemplate.opsForValue().get(
                    RedisConstants.ARTICLE_DETAIL_KEY_PREFIX + articleId);
            assertThat(cached).isNull();
        } catch (Exception e) {
            // Redis 不可用，跳过缓存断言
        }
    }
}
