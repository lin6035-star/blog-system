package com.hailin.blogsystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.dto.ArticlesDTO;
import com.hailin.blogsystem.entity.vo.ArticleDetailVO;
import com.hailin.blogsystem.entity.vo.PageVO;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

public interface ArticlesService extends IService<Articles>{
    ArticleDetailVO getPublicArticleById(Long id);

    PageVO<ArticleDetailVO> getArticles(Long page, Long pageSize, String keyword, Long categoryId, String sort);

    ArticleDetailVO getArticlesById(Long id);

    Long writeArticle(ArticlesDTO articlesDTO);

    void updateArticle(Long id, ArticlesDTO articlesDTO);

    /**
     * V3.4 Agent 受控写（UPDATE_ARTICLE_TITLE）专用：只改自己文章的标题（字段级，绝不走全量 updateArticle）。
     * expectedOldTitle = 提案锚定的旧标题（proposal.articleTitle），条件更新 WHERE title = expectedOldTitle，
     * 影响 0 行 = 标题已被并发修改 → 拒绝不覆盖（用户需重新发起）；newTitle 非空/trim 由调用方负责。
     * 副作用：清详情缓存 + 列表缓存；已发布文章刷新 RAG 索引（doc 含标题）。
     */
    void updateArticleTitle(Long id, String expectedOldTitle, String newTitle, Long userId);

    void deleteArticle(Long id);

    void hideArticle(Long id);

    void publishArticle(Long id);

    void syncViewCountToDb();

    PageVO<ArticleDetailVO> getHotArticles(Long page, Long pageSize);

    void rebuildArticleHotRank();

    PageVO<ArticleDetailVO> getPublicUserArticles(Long id,Long page,Long pageSize);

    PageVO<ArticleDetailVO> getPublicUserLiked(Long id, Long page, Long pageSize);

    PageVO<ArticleDetailVO> getPublicUserFavorited(Long id, Long page, Long pageSize);

    PageVO<ArticleDetailVO> getPublicCommented(Long id, Long page, Long pageSize);

    void shareArticle(Long id);
}
