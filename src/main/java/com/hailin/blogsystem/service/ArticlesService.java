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
