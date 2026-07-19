package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.entity.ArticleLikes;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.mapper.ArticleLikesMapper;
import com.hailin.blogsystem.mapper.ArticlesMapper;
import com.hailin.blogsystem.service.ArticleLikesService;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class ArticleLikesServiceImpl extends ServiceImpl<ArticleLikesMapper, ArticleLikes> implements ArticleLikesService {

    private final ArticlesMapper articlesMapper;
    private final StringRedisTemplate stringRedisTemplate;

    @Override  //1.点赞文章
    @Transactional
    public void likeArticle(Long articleId) {
        Long userId = UserContext.get();
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }

        boolean exists = lambdaQuery()
                .eq(ArticleLikes::getArticleId, articleId)
                .eq(ArticleLikes::getUserId, userId)
                .exists();

        if (exists) {
            throw new IllegalArgumentException("您已点过赞");
        }

        ArticleLikes articleLikes = new ArticleLikes();
        articleLikes.setArticleId(articleId);
        articleLikes.setUserId(userId);
        articleLikes.setCreateTime(LocalDateTime.now());
        save(articleLikes);

        articlesMapper.update(null,
                new LambdaUpdateWrapper<Articles>()
                        .eq(Articles::getId, articleId)
                        .setSql("like_count = like_count + 1"));
        //点赞成功后，将数量加入redis
        String key = RedisConstants.ARTICLE_LIKED_USER_KEY_PREFIX + userId;
        stringRedisTemplate.opsForSet().add(key,String.valueOf(articleId));

        //点赞成功后，获得对应的score，用户zset排名
        stringRedisTemplate.opsForZSet()
                .incrementScore(RedisConstants.ARTICLE_HOT_KEY,String.valueOf(articleId),
                        RedisConstants.ARTICLE_LIKE_HOT_SCORE);

        //确认点赞时维护 loaded 后的 Set
        stringRedisTemplate.opsForSet()
                .add(RedisConstants.ARTICLE_LIKED_USER_KEY_PREFIX + userId, String.valueOf(articleId));

        stringRedisTemplate.opsForValue()
                .set(RedisConstants.ARTICLE_LIKED_USER_KEY_PREFIX + userId + ":loaded", "1", 30, TimeUnit.MINUTES);

        stringRedisTemplate.delete(RedisConstants.ARTICLE_DETAIL_KEY_PREFIX + articleId);
    }

    @Override  //2.取消点赞文章
    @Transactional
    public void cancelLikeArticle(Long articleId) {
        Long userId = UserContext.get();
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }

        ArticleLikes one = lambdaQuery()
                .eq(ArticleLikes::getArticleId, articleId)
                .eq(ArticleLikes::getUserId, userId)
                .one();

        if (one == null) {
            throw new IllegalArgumentException("您未点过赞，出现错误");
        }

        removeById(one.getId());

        articlesMapper.update(null,
                new LambdaUpdateWrapper<Articles>()
                        .eq(Articles::getId, articleId)
                        .setSql("like_count = GREATEST(like_count - 1, 0)"));

        //取消点赞后，将数量加入redis
        String key = RedisConstants.ARTICLE_LIKED_USER_KEY_PREFIX +userId;
        stringRedisTemplate.opsForSet().remove(key,String.valueOf(articleId));

        //取消点赞后，减去对应的score，用户zset排名
        stringRedisTemplate.opsForZSet().incrementScore(RedisConstants.ARTICLE_HOT_KEY,
                String.valueOf(articleId),
                RedisConstants.ARTICLE_UNLIKE_HOT_SCORE);

        //取消点赞时维护 loaded 后的 Set
        stringRedisTemplate.opsForSet()
                .remove(RedisConstants.ARTICLE_LIKED_USER_KEY_PREFIX + userId, String.valueOf(articleId));

        stringRedisTemplate.opsForValue()
                .set(RedisConstants.ARTICLE_LIKED_USER_KEY_PREFIX + userId + ":loaded", "1", 30, TimeUnit.MINUTES);

        stringRedisTemplate.delete(RedisConstants.ARTICLE_DETAIL_KEY_PREFIX + articleId);
    }

}
