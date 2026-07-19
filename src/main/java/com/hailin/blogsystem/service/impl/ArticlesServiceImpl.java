package com.hailin.blogsystem.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.entity.ArticleFavorites;
import com.hailin.blogsystem.entity.ArticleLikes;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.Category;
import com.hailin.blogsystem.entity.Users;
import com.hailin.blogsystem.entity.dto.ArticlesDTO;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.mapper.ArticleFavoritesMapper;
import com.hailin.blogsystem.mapper.ArticleLikesMapper;
import com.hailin.blogsystem.mapper.ArticlesMapper;
import com.hailin.blogsystem.mapper.CategoryMapper;
import com.hailin.blogsystem.mapper.UsersMapper;
import com.hailin.blogsystem.service.ArticlesService;
import com.hailin.blogsystem.entity.vo.ArticleDetailVO;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ArticlesServiceImpl extends ServiceImpl<ArticlesMapper, Articles> implements ArticlesService {

    private final UsersMapper usersMapper;
    private final CategoryMapper categoryMapper;
    private final ArticleLikesMapper articleLikesMapper;
    private final ArticleFavoritesMapper articleFavoritesMapper;

    private final ObjectMapper objectMapper;

    private final StringRedisTemplate stringRedisTemplate;

    @Override  //1.获取公开文章列表
    public PageVO<ArticleDetailVO> getArticles(Long page, Long pageSize, String keyword, Long categoryId, String sort) {  //1.获取公开文章列表

        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword;
        String normalizedSort = "recommend".equals(sort) ? "recommend" : "latest";
        boolean cacheable = normalizedKeyword == null;
        String cacheKey = cacheable ? buildArticleListCacheKey(page,pageSize,categoryId,normalizedSort) : null;

        if(cacheable){
            PageVO<ArticleDetailVO> cachedPage = getArticleListFromCache(cacheKey);

            if(cachedPage != null){
                fillArticleLiked(cachedPage.getList());
                fillArticleFavorited(cachedPage.getList());
                fillArticleViewCount(cachedPage.getList());
                return cachedPage;
            }
        }

        Page<Articles> pageResult = lambdaQuery()
                .eq(Articles::getStatus, BlogConstants.ArticlesStatus.PUBLISHED)
                .like(normalizedKeyword != null, Articles::getTitle, normalizedKeyword)
                .eq(categoryId != null, Articles::getCategoryId, categoryId)
                .orderByDesc("recommend".equals(normalizedSort), Articles::getViewCount)
                .orderByDesc(!"recommend".equals(normalizedSort), Articles::getPublishedAt)
                .orderByDesc(Articles::getId)
                .page(new Page<>(page,pageSize));

        List<ArticleDetailVO> list = pageResult.getRecords()
                .stream()
                .map(ArticleDetailVO::from)
                .toList();
        fillArticleMeta(list);

        PageVO<ArticleDetailVO> result = new PageVO<>(
                list,
                pageResult.getTotal(),
                page,
                pageSize
        );

        if(cacheable){
            saveArticleListToCache(cacheKey,result);
        }

        fillArticleLiked(list);
        fillArticleFavorited(list);
        fillArticleViewCount(list);

        return result;
    }

    private String buildArticleListCacheKey(Long page, Long pageSize, Long categoryId, String sort) {
        String categoryPart = categoryId == null ? "all" : String.valueOf(categoryId);
        return RedisConstants.ARTICLE_LIST_KEY_PREFIX
                + "page:" + page
                + ":size:" + pageSize
                + ":category:" + categoryPart
                + ":sort:" + sort;
    }

    private PageVO<ArticleDetailVO> getArticleListFromCache(String key) {
        String json = null;

        try{
            json = stringRedisTemplate.opsForValue().get(key);
        }catch(Exception e){
            // Redis读取失败不影响公开文章列表，继续查数据库
            return null;
        }

        if(json == null || json.isBlank()){
            return null;
        }

        try{
            return objectMapper.readValue(json,new TypeReference<PageVO<ArticleDetailVO>>(){});
        }catch(JsonProcessingException e){
            try{
                stringRedisTemplate.delete(key);
            }catch(Exception ignored){
                // Redis删除失败不影响，继续查数据库
            }
            return null;
        }
    }

    private void saveArticleListToCache(String key, PageVO<ArticleDetailVO> pageVO) {
        if(pageVO == null){
            return;
        }

        try{
            stringRedisTemplate.opsForValue()
                    .set(
                            key,
                            objectMapper.writeValueAsString(pageVO),
                            RedisConstants.ARTICLE_LIST_TTL_MINUTES,
                            TimeUnit.MINUTES
                    );
        }catch(Exception e){
            // 缓存失败不影响文章列表返回
        }
    }

    @Override  //2.获取公开文章详情
    public ArticleDetailVO getPublicArticleById(Long id) {  //2.获取公开文章详情

        String key = RedisConstants.ARTICLE_DETAIL_KEY_PREFIX + id;
        String json = null;

        try{
            json = stringRedisTemplate.opsForValue().get(key);
        }
        catch (Exception e){
            // Redis读取失败不影响文章详情，继续走缓存未命中的 DB 查询逻辑
        }

        if(RedisConstants.CACHE_NULL_VALUE.equals(json)){
            return null;
        }

        ArticleDetailVO vo = getArticleDetailFromCache(id);

        Long userId = UserContext.get();
        if(vo == null){
            vo = getArticleDetailFromDb(id);

            if(vo == null){
                return null;
            }

            saveArticleDetailToCache(id,vo);
        }

        if(userId == null || !userId.equals(vo.getAuthorId())){
            String viewKey = RedisConstants.ARTICLE_VIEW_KEY_PREFIX + id;
            try{
                stringRedisTemplate.opsForValue().increment(viewKey);

                stringRedisTemplate.opsForZSet()
                        .incrementScore(RedisConstants.ARTICLE_HOT_KEY,
                                String.valueOf(id),
                                RedisConstants.ARTICLE_VIEW_HOT_SCORE);
            }catch(Exception e){
                // Redis统计失败不影响文章详情返回
            }
        }

        fillArticleLiked(vo);
        fillArticleFavorited(vo);
        fillArticleViewCount(List.of(vo));

        return vo;
    }


    @Override
    public ArticleDetailVO getArticlesById(Long id) {  //3.获取我自己的文章详情
        Articles articles = getById(id);
        if(articles == null){
            throw new IllegalArgumentException("未找到该博文");
        }
        Long userId = UserContext.get();
        if(!articles.getAuthorId().equals(userId)){
            throw new IllegalArgumentException("无权操作该文章");
        }


        ArticleDetailVO articlesVO = new ArticleDetailVO();

        BeanUtil.copyProperties(articles,articlesVO);

        return articlesVO;
    }

    private void fillArticleMeta(ArticleDetailVO article) {
        if (article == null) {
            return;
        }
        fillArticleMeta(List.of(article));
    }

    private void fillArticleMeta(List<ArticleDetailVO> articles) {
        if (articles == null || articles.isEmpty()) {
            return;
        }

        List<Long> authorIds = articles.stream()
                .map(ArticleDetailVO::getAuthorId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<Long> categoryIds = articles.stream()
                .map(ArticleDetailVO::getCategoryId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<Long, Users> usersById = authorIds.isEmpty()
                ? Map.of()
                : usersMapper.selectBatchIds(authorIds)
                .stream()
                .collect(Collectors.toMap(Users::getId, Function.identity()));
        Map<Long, Category> categoriesById = categoryIds.isEmpty()
                ? Map.of()
                : categoryMapper.selectBatchIds(categoryIds)
                .stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));

        for (ArticleDetailVO article : articles) {
            Users author = usersById.get(article.getAuthorId());
            if (author != null) {
                article.setAuthorName(author.getNickname());
            }

            Category category = categoriesById.get(article.getCategoryId());
            if (category != null) {
                article.setCategoryName(category.getName());
            }
        }
    }

    //redis化
    private void fillArticleLiked(List<ArticleDetailVO> articles) {
        if (articles == null || articles.isEmpty()) {
            return;
        }

        Long currentUserId = UserContext.get();
        if (currentUserId == null) {
            return;
        }

        String key = RedisConstants.ARTICLE_LIKED_USER_KEY_PREFIX + currentUserId;
        String loadedKey = key + ":loaded";

        Set<String> likedIdStrings = null;

        try{
            if(Boolean.TRUE.equals(stringRedisTemplate.hasKey(loadedKey))){
                likedIdStrings = stringRedisTemplate.opsForSet().members(key);
            }
        }catch(Exception e){
            // Redis读取失败，下面兜底查数据库
        }

        if(likedIdStrings == null){
            List<ArticleLikes> likes = articleLikesMapper.selectList(
                    new LambdaQueryWrapper<ArticleLikes>()
                            .eq(ArticleLikes::getUserId, currentUserId));

            likedIdStrings = likes.stream()
                    .map(like -> String.valueOf(like.getArticleId()))
                    .collect(Collectors.toSet());

            try{
                if(!likedIdStrings.isEmpty()){
                    stringRedisTemplate.opsForSet().add(key,likedIdStrings.toArray(new String[0]));
                }

                stringRedisTemplate.opsForValue().set(loadedKey, "1", 30, TimeUnit.MINUTES);
            }catch(Exception e){
                // Redis回填失败不影响点赞状态计算
            }
        }

        Set<Long> likedArticleIds = likedIdStrings == null
                ? Set.of()
                : likedIdStrings.stream()
                .map(Long::valueOf)
                .collect(Collectors.toSet());

        for (ArticleDetailVO article : articles) {
            article.setLiked(likedArticleIds.contains(article.getId()) ? 1 : 0);
        }
    }

    private void fillArticleLiked(ArticleDetailVO article) {
        if (article == null) {
            return;
        }
        fillArticleLiked(List.of(article));
    }

    private void fillArticleFavorited(List<ArticleDetailVO> articles) {
        if (articles == null || articles.isEmpty()) {
            return;
        }

        Long currentUserId = UserContext.get();
        if (currentUserId == null) {
            return;
        }

        List<Long> articleIds = articles.stream()
                .map(ArticleDetailVO::getId)
                .filter(Objects::nonNull)
                .toList();

        if (articleIds.isEmpty()) {
            return;
        }

        String key = RedisConstants.ARTICLE_FAVORITED_USER_KEY_PREFIX + currentUserId;
        String loadedKey = key + ":loaded";

        Set<String> favoritedIdStrings = null;

        try{
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(loadedKey))) {
                favoritedIdStrings = stringRedisTemplate.opsForSet().members(key);
            }
        }catch(Exception e){
            // Redis读取失败，下面兜底查数据库
        }

        if(favoritedIdStrings == null){
            List<ArticleFavorites> favorites = articleFavoritesMapper.selectList(
                    new LambdaQueryWrapper<ArticleFavorites>()
                            .eq(ArticleFavorites::getUserId, currentUserId)
            );

            favoritedIdStrings = favorites.stream()
                    .map(favorite -> String.valueOf(favorite.getArticleId()))
                    .collect(Collectors.toSet());

            try{
                if (!favoritedIdStrings.isEmpty()) {
                    stringRedisTemplate.opsForSet()
                            .add(key, favoritedIdStrings.toArray(new String[0]));
                }

                stringRedisTemplate.opsForValue().set(
                        loadedKey,
                        "1",
                        30,
                        TimeUnit.MINUTES
                );
            }catch(Exception e){
                // Redis回填失败不影响收藏状态计算
            }
        }

        Set<Long> favoritedArticleIds = favoritedIdStrings == null
                ? Set.of()
                : favoritedIdStrings.stream()
                .map(Long::valueOf)
                .collect(Collectors.toSet());

        for (ArticleDetailVO article : articles) {
            article.setFavorited(favoritedArticleIds.contains(article.getId()) ? 1 : 0);
        }
    }

    private void fillArticleFavorited(ArticleDetailVO article) {
        if (article == null) {
            return;
        }
        fillArticleFavorited(List.of(article));
    }


    @Override  //4.创建文章
    public Long writeArticle(ArticlesDTO articlesDTO) {
        Articles articles = new Articles();
        BeanUtil.copyProperties(articlesDTO,articles);

        articles.setAuthorId(UserContext.get());
        articles.setCreatedAt(LocalDateTime.now());
        articles.setUpdatedAt(LocalDateTime.now());
        articles.setPublishedAt(null);
        syncPublishedAt(articles);

        save(articles);
        if(Objects.equals(articles.getStatus(), BlogConstants.ArticlesStatus.PUBLISHED)){
            deleteArticleListCache();
        }
        return articles.getId();
    }


    @Override  //5.更新自己的文章
    public void updateArticle(Long id, ArticlesDTO articlesDTO) {

        Articles articles = getById(id);
        if(articles == null){
            throw new IllegalArgumentException("未找到该博文");
        }
        Long userId = UserContext.get();
        if(!articles.getAuthorId().equals(userId)){
            throw new IllegalArgumentException("无权操作该文章");
        }

        LocalDateTime previousPublishedAt = articles.getPublishedAt();
        BeanUtil.copyProperties(articlesDTO,articles);

        articles.setUpdatedAt(LocalDateTime.now());
        articles.setAuthorId(UserContext.get());
        articles.setPublishedAt(previousPublishedAt);
        syncPublishedAt(articles);

        updateById(articles);

        deleteArticleDetailCache(id);
        deleteArticleListCache();
    }


    @Override  //6.删除自己的文章
    public void deleteArticle(Long id) {
        Articles articles = getById(id);
        if(articles == null){
            throw new IllegalArgumentException("未找到该博文");
        }
        Long userId = UserContext.get();
        if(!articles.getAuthorId().equals(userId)){
            throw new IllegalArgumentException("无权操作该文章");
        }

        removeById(id);

        deleteArticleDetailCache(id);
        deleteArticleListCache();
    }


    @Override  //7.隐藏自己的文章
    public void hideArticle(Long id) {
        Articles articles = getById(id);
        if(articles == null){
            throw  new IllegalArgumentException("该文章不存在!");
        }

        Long userId = UserContext.get();
        if(!articles.getAuthorId().equals(userId)){
            throw new IllegalArgumentException("无权操作该文章");
        }

        articles.setStatus(BlogConstants.ArticlesStatus.HIDDEN);
        articles.setUpdatedAt(LocalDateTime.now());

        updateById(articles);

        deleteArticleDetailCache(id);
        deleteArticleListCache();
    }


    @Override  //8.发布自己的文章
    public void publishArticle(Long id) {
        Articles articles = getById(id);
        if(articles == null){
            throw  new IllegalArgumentException("该文章不存在!");
        }
        Long userId = UserContext.get();
        if(!articles.getAuthorId().equals(userId)){
            throw new IllegalArgumentException("无权操作该文章");
        }


        articles.setStatus(BlogConstants.ArticlesStatus.PUBLISHED);
        articles.setUpdatedAt(LocalDateTime.now());
        articles.setPublishedAt(LocalDateTime.now());

        updateById(articles);

        deleteArticleDetailCache(id);
        deleteArticleListCache();
    }


    @Override  //9.获取热度前十的文章
    public PageVO<ArticleDetailVO> getHotArticles(Long page, Long pageSize) {
        // 1. 计算 Redis ZSET 的起止下标
        long start = (page - 1) * pageSize;
        long end = start + pageSize - 1;
        // 2. 从Redis取出热门文章的id（用reverseRange，分数从高到低）
        Set<String> hotArticleIdsString = null;

        try{
            hotArticleIdsString = stringRedisTemplate.opsForZSet()
                    .reverseRange(RedisConstants.ARTICLE_HOT_KEY, start, end);
        }
        catch(Exception e){
            // Redis读取失败，下面走 DB 兜底
        }

        // 3. Redis没数据就兜底查数据库viewCount
        if(hotArticleIdsString == null || hotArticleIdsString.isEmpty()){
            Page<Articles> pageResult = lambdaQuery()
                    .eq(Articles::getStatus,BlogConstants.ArticlesStatus.PUBLISHED)
                    .orderByDesc(Articles::getViewCount)
                    .page(new Page<>(page,pageSize));

            List<ArticleDetailVO> list = pageResult.getRecords().stream()
                    .map(ArticleDetailVO::from)
                    .toList();

            fillArticleMeta(list);
            fillArticleLiked(list);
            fillArticleFavorited(list);
            fillArticleViewCount(list);

            return new PageVO<>(list, pageResult.getTotal(), page, pageSize);
        }

        // 4. Redis 有数据就按 id 查数据库
        List<Long> hotArticleIds = hotArticleIdsString.stream()
                .map(Long::valueOf)
                .toList();  //String id 转 Long id
        //5. 按 Redis 顺序组装 VO
        List<Articles> articles = lambdaQuery().in(Articles::getId, hotArticleIds)
                .eq(Articles::getStatus, BlogConstants.ArticlesStatus.PUBLISHED)
                .list();
        //6. 转 Map，再按 Redis 顺序组装  in查出来的数据顺序不可靠
        Map<Long, Articles> articlesMap = articles.stream()
                .collect(Collectors.toMap(Articles::getId, Function.identity()));
        List<ArticleDetailVO> list = hotArticleIds.stream()
                .map(articlesMap::get)
                .filter(Objects::nonNull)
                .map(ArticleDetailVO::from)
                .toList();

        fillArticleMeta(list);
        fillArticleLiked(list);
        fillArticleFavorited(list);
        fillArticleViewCount(list);

        Long total = null;

        try{
            total = stringRedisTemplate.opsForZSet()
                    .zCard(RedisConstants.ARTICLE_HOT_KEY);
        }
        catch (Exception e){
            total = (long) list.size();
        }

        return new PageVO<>(
                list,
                total == null ? 0 : total,
                page,
                pageSize
        );
    }


    private void syncPublishedAt(Articles articles) {
        if (Objects.equals(articles.getStatus(), BlogConstants.ArticlesStatus.PUBLISHED)
                && articles.getPublishedAt() == null) {
            articles.setPublishedAt(LocalDateTime.now());
        }
    }

    //关于redis存储浏览量
    private void fillArticleViewCount(List<ArticleDetailVO> articles){
        if(articles == null || articles.isEmpty()){
            return;
        }

        String key;
        for(ArticleDetailVO article : articles){
            key = RedisConstants.ARTICLE_VIEW_KEY_PREFIX + article.getId();
            String redisViewCount = null;

            try{
                redisViewCount = stringRedisTemplate.opsForValue().get(key);
            }catch(Exception e){
                // Redis读取失败时只显示数据库里的浏览量
                continue;
            }

            if(redisViewCount == null){
                continue;
            }

            int baseViewCount = article.getViewCount() == null ? 0 : article.getViewCount();
            article.setViewCount((baseViewCount + Integer.parseInt(redisViewCount)));
        }
    }

    @Override  //将存储在redis的浏览量加入到数据库，改数据库
    public void syncViewCountToDb(){
        Set<String> keys = stringRedisTemplate.keys(RedisConstants.ARTICLE_VIEW_KEY_PREFIX + "*");

        if(keys == null || keys.isEmpty())
            return;

        String redisViewCount;
        for(String key : keys){
            redisViewCount = stringRedisTemplate.opsForValue().get(key);

            if(redisViewCount == null)
                continue;

            Long articleId = Long.valueOf(key.substring(RedisConstants.ARTICLE_VIEW_KEY_PREFIX.length()));
            Integer increment = Integer.valueOf(redisViewCount);

            if(increment <= 0){
                stringRedisTemplate.delete(key);
                continue;
            }

            lambdaUpdate()
                    .eq(Articles::getId,articleId)
                    .setSql("view_count = view_count + " + increment)
                    .update();

            stringRedisTemplate.delete(key);
        }
    }

    @Override  //重建热度榜
    public void rebuildArticleHotRank() {
        List<Articles> articles = lambdaQuery()
                .eq(Articles::getStatus, BlogConstants.ArticlesStatus.PUBLISHED)
                .list();

        stringRedisTemplate.delete(RedisConstants.ARTICLE_HOT_KEY);

        for(Articles article : articles){
            String viewKey = RedisConstants.ARTICLE_VIEW_KEY_PREFIX + article.getId();
            String redisViewCount = stringRedisTemplate.opsForValue().get(viewKey);

            int redisViewIncrement = redisViewCount == null ? 0 : Integer.parseInt(redisViewCount);

            double score =
                    safe(article.getViewCount()) * RedisConstants.ARTICLE_VIEW_HOT_SCORE
                            + redisViewIncrement * RedisConstants.ARTICLE_VIEW_HOT_SCORE
                            + safe(article.getLikeCount()) * RedisConstants.ARTICLE_LIKE_HOT_SCORE
                            + safe(article.getFavoriteCount()) * RedisConstants.ARTICLE_FAVORITE_HOT_SCORE
                            + safe(article.getCommentCount()) * RedisConstants.ARTICLE_COMMENT_HOT_SCORE;

            stringRedisTemplate.opsForZSet()
                    .add(RedisConstants.ARTICLE_HOT_KEY, String.valueOf(article.getId()), score);
        }


    }

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }


    //拆一个“查文章基础详情”的方法
    private ArticleDetailVO getArticleDetailFromDb(Long id){
        Articles articles = lambdaQuery()
                .eq(Articles::getId,id)
                .eq(Articles::getStatus,BlogConstants.ArticlesStatus.PUBLISHED)
                .one();

        if(articles == null){
            try{
                stringRedisTemplate.opsForValue().set(
                        RedisConstants.ARTICLE_DETAIL_KEY_PREFIX + id,
                        RedisConstants.CACHE_NULL_VALUE,
                        RedisConstants.CACHE_NULL_TTL_MINUTES,
                        TimeUnit.MINUTES
                );
            }catch(Exception e){
                // 空值缓存写入失败不影响查询结果
            }

            return null;
        }

        ArticleDetailVO vo = ArticleDetailVO.from(articles);

        fillArticleMeta(vo);

        return vo;
    }

    //从缓存中拿文章详情的方法
    private ArticleDetailVO getArticleDetailFromCache(Long id){
        String key = RedisConstants.ARTICLE_DETAIL_KEY_PREFIX + id;
        String json = null;

        try{
            json = stringRedisTemplate.opsForValue().get(key);
        }catch(Exception e){
            // Redis读取失败，交给外层继续查数据库
            return null;
        }

        if(json == null || json.isBlank()){
            return null;
        }

        try{
            return objectMapper.readValue(json,ArticleDetailVO.class);
        } catch (JsonProcessingException e) {
            try{
                stringRedisTemplate.delete(key); //这里为什么解析失败要删缓存？
            }catch(Exception ignored){
                // Redis删除失败不影响，交给外层继续查数据库
            }
            return null;  //因为 Redis 里如果有脏数据，继续留着每次都会解析失败。删掉后下次可以走数据库重建
        }
    }

    //写入缓存的方法
    private void saveArticleDetailToCache(Long id,ArticleDetailVO articleDetailVO){
        if(articleDetailVO == null){
            return;
        }

        String key = RedisConstants.ARTICLE_DETAIL_KEY_PREFIX + id;

        try{  //注意这里不要抛异常。Redis 缓存失败，最多就是慢一点，不能影响用户看文章
            String json = objectMapper.writeValueAsString(articleDetailVO);
            stringRedisTemplate.opsForValue()
                    .set(
                            key,
                            json,
                            RedisConstants.ARTICLE_DETAIL_TTL_MINUTES,
                            TimeUnit.MINUTES
                    );
        } catch(Exception e){
            //缓存失败不影响，后面可直接查询数据
        }
    }

    //文章变更时删除缓存
    private void deleteArticleDetailCache(Long id) {
        try{
            stringRedisTemplate.delete(RedisConstants.ARTICLE_DETAIL_KEY_PREFIX + id);
        }catch(Exception e){
            // Redis删除失败不影响文章变更本身
        }
    }

    private void deleteArticleListCache() {
        try{
            Set<String> keys = stringRedisTemplate.keys(RedisConstants.ARTICLE_LIST_KEY_PREFIX + "*");
            if(keys != null && !keys.isEmpty()){
                stringRedisTemplate.delete(keys);
            }
        }catch(Exception e){
            // Redis删除失败不影响文章变更本身
        }
    }
}
