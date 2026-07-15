package com.hailin.blogsystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hailin.blogsystem.entity.ArticleFavorites;

public interface ArticleFavoritesService extends IService<ArticleFavorites> {
    void favoriteArticle(Long articleId);

    void cancelFavorite(Long articleId);
}
