package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hailin.blogsystem.entity.ArticleFavorites;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.mapper.ArticleFavoritesMapper;
import com.hailin.blogsystem.mapper.ArticlesMapper;
import com.hailin.blogsystem.service.ArticleFavoritesService;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ArticleFavoritesServiceImpl extends ServiceImpl<ArticleFavoritesMapper, ArticleFavorites> implements ArticleFavoritesService {

    private final ArticlesMapper articlesMapper;

    @Override  //1.收藏文章
    public void favoriteArticle(Long articleId) {
        Long userId = UserContext.get();

        boolean exists = lambdaQuery()
                .eq(ArticleFavorites::getArticleId, articleId)
                .eq(ArticleFavorites::getUserId, userId).exists();

        if(exists){
            throw new IllegalArgumentException("已经收藏过该博文,不能重复收藏");
        }
        ArticleFavorites articleFavorites = new ArticleFavorites();
        articleFavorites.setArticleId(articleId);
        articleFavorites.setUserId(userId);
        articleFavorites.setCreateTime(LocalDateTime.now());
        save(articleFavorites);

        articlesMapper.update(null,
                new LambdaUpdateWrapper<Articles>()
                        .eq(Articles::getId,articleId)
                        .setSql("favorite_count = favorite_count + 1"));
    }


    @Override  //2.取消收藏文章
    public void cancelFavorite(Long articleId) {
        Long userId = UserContext.get();

        ArticleFavorites one = lambdaQuery()
                .eq(ArticleFavorites::getArticleId, articleId)
                .eq(ArticleFavorites::getUserId, userId).one();

        if(one == null){
            throw new IllegalArgumentException("您未收藏过，出现错误");
        }

        removeById(one);

        articlesMapper.update(null,
                new LambdaUpdateWrapper<Articles>().eq(Articles::getId,articleId)
                        .setSql("favorite_count = GREATEST(favorite_count - 1, 0)"));
    }
}
