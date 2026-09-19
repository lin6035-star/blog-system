package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hailin.blogsystem.component.AfterCommitExecutor;
import com.hailin.blogsystem.component.UserSetCache;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.entity.ArticleFavorites;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.mapper.ArticleFavoritesMapper;
import com.hailin.blogsystem.mapper.ArticlesMapper;
import com.hailin.blogsystem.service.ArticleFavoritesService;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class ArticleFavoritesServiceImpl extends ServiceImpl<ArticleFavoritesMapper, ArticleFavorites> implements ArticleFavoritesService {

    private final ArticlesMapper articlesMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final UserSetCache userSetCache;
    private final AfterCommitExecutor afterCommitExecutor;

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
        //点击收藏增加相应的score分数
        stringRedisTemplate.opsForZSet().incrementScore(RedisConstants.ARTICLE_HOT_KEY,
                String.valueOf(articleId),RedisConstants.ARTICLE_FAVORITE_HOT_SCORE);

        //维护用户收藏集合：仅在集合已加载时同步，未加载时什么都不做——
        //保持"未加载"让下次读从 DB 全量重建，否则会留下一个只有这一条记录的集合却自称全量
        userSetCache.addIfLoaded(
                RedisConstants.ARTICLE_FAVORITED_USER_KEY_PREFIX + userId, String.valueOf(articleId));

        evictArticleDetailCache(articleId);
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

        //点击取消收藏增加相应的score分数
        stringRedisTemplate.opsForZSet().incrementScore(RedisConstants.ARTICLE_HOT_KEY,
                String.valueOf(articleId),RedisConstants.ARTICLE_UNFAVORITE_HOT_SCORE);

        //维护用户收藏集合：取消最后一项时状态会转为 EMPTY，不留「全量标记 + 空集合」
        userSetCache.removeIfLoaded(
                RedisConstants.ARTICLE_FAVORITED_USER_KEY_PREFIX + userId, String.valueOf(articleId));

        evictArticleDetailCache(articleId);
    }

    /**
     * 收藏数会展示在文章详情页，所以收藏/取消要失效详情缓存。
     * 走 {@link AfterCommitExecutor}（本类当前无事务，等价于立即执行，但去掉 `@Transactional`
     * 或将来补上时行为自动正确）；原来这里没有 try-catch，Redis 一抖会让收藏直接失败——
     * 缓存删不掉不该拦着用户收藏。
     */
    private void evictArticleDetailCache(Long articleId) {
        String key = RedisConstants.ARTICLE_DETAIL_KEY_PREFIX + articleId;
        afterCommitExecutor.execute(() -> {
            try {
                stringRedisTemplate.delete(key);
            } catch (Exception e) {
                log.warn("[CACHE-EVICT-FAIL] 文章详情缓存删除失败，将靠 TTL 兜底: key={}", key, e);
            }
        });
    }
}
