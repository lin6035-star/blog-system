package com.hailin.blogsystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hailin.blogsystem.entity.ArticleLikes;

public interface ArticleLikesService extends IService<ArticleLikes> {
    void likeArticle(Long articleId);

    void cancelLikeArticle(Long articleId);
}
