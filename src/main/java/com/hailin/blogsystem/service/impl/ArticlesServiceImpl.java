package com.hailin.blogsystem.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.hailin.blogsystem.ai.rag.ArticleRagIndexService;
import com.hailin.blogsystem.ai.rag.ArticleRagSyncService;
import com.hailin.blogsystem.component.AfterCommitExecutor;
import com.hailin.blogsystem.component.CacheTtlSupport;
import com.hailin.blogsystem.component.RedisKeyScanner;
import com.hailin.blogsystem.component.UserActivityTracker;
import com.hailin.blogsystem.component.UserSetCache;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.entity.*;
import com.hailin.blogsystem.entity.dto.ArticlesDTO;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.mapper.*;
import com.hailin.blogsystem.service.ArticlesService;
import com.hailin.blogsystem.entity.vo.ArticleDetailVO;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ArticlesServiceImpl extends ServiceImpl<ArticlesMapper, Articles> implements ArticlesService {

    /** 详情缓存互斥锁 TTL：短 TTL——持锁进程崩溃后最多 5 秒自动释放（对比 Workflow 锁的 120s） */
    private static final long DETAIL_LOCK_TTL_SECONDS = 5L;
    /** 未抢到锁时的短轮询：50ms × 10 ≈ 500ms 上限，超时 fail-open 自己查库 */
    private static final long DETAIL_LOCK_POLL_INTERVAL_MILLIS = 50L;
    private static final int DETAIL_LOCK_POLL_MAX_ATTEMPTS = 10;
    private static final String DETAIL_NULL_NOT_FOUND_VALUE = RedisConstants.CACHE_NULL_VALUE + ":not_found";
    private static final String DETAIL_NULL_INVISIBLE_PREFIX = RedisConstants.CACHE_NULL_VALUE + ":invisible:";

    /** 释放锁：只有 token 匹配才删——否则慢请求会删掉别人的锁，互斥形同虚设 */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """,
            Long.class
    );

    /** 浏览量自增 + 兜底 TTL（一次 Lua 完成，避免 INCR 与 EXPIRE 之间的故障与竞态）。
     *  判 TTL &lt; 0 而非 INCR == 1：后者补不上"上线前就存在的无 TTL 旧 key"。 */
    private static final DefaultRedisScript<Long> VIEW_INCR_SCRIPT = new DefaultRedisScript<>(
            """
            local current = redis.call('INCR', KEYS[1])
            if redis.call('TTL', KEYS[1]) < 0 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """,
            Long.class
    );

    /** 原子取走浏览量增量：GET + DEL 一次 Lua（GETDEL 需 Redis 6.2+，这里兼容更低版本） */
    private static final DefaultRedisScript<String> VIEW_TAKE_SCRIPT = new DefaultRedisScript<>(
            """
            local value = redis.call('GET', KEYS[1])
            if value then
                redis.call('DEL', KEYS[1])
            end
            return value
            """,
            String.class
    );

    /** 记录独立访客 + 兜底 TTL（一次 Lua 完成）。
     *  与 VIEW_INCR_SCRIPT 同构、理由也相同：PFADD 与 EXPIRE 分两次调用，中间故障会留下
     *  **无 TTL 的 key**——而"允许丢失的数据都有 TTL"是 volatile-lru 能正常淘汰的前提。
     *  判 TTL &lt; 0 而非"首次 PFADD"：补得上任何原因造成的无 TTL 残留。 */
    private static final DefaultRedisScript<Long> UV_ADD_SCRIPT = new DefaultRedisScript<>(
            """
            redis.call('PFADD', KEYS[1], ARGV[1])
            if redis.call('TTL', KEYS[1]) < 0 then
                redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            return 1
            """,
            Long.class
    );

    /**
     * 热度榜原子替换：EXISTS 检查 + RENAME + EXPIRE 一次完成。
     *
     * **为什么不能分三步写**：
     * 1. `RENAME` 的源 key 不存在会直接抛错（`ERR no such key`）——两个执行者同时重建时，
     *    先完成的那个已经把临时 key RENAME 走了，后一个就会炸。生产只有定时任务一个调用方
     *    （调度池 size=1）撞不上，但测试 / 手工触发会成为第二个调用方——这个错就是这么暴露的
     * 2. `EXPIRE` 必须跟在 `RENAME` **之后**：`RENAME` 会把目标 key 的 TTL 换成源 key 的，
     *    而临时 key 每轮都是新建、无 TTL，写在前面等于白写
     *
     * 打包之后这两个约束都不再由调用方保证。
     *
     * KEYS[1] = 临时榜，KEYS[2] = 正式榜；ARGV[1] = TTL(秒)
     *
     * @return 1 = 替换成功；0 = 临时榜不存在（已有并发重建完成本轮），本次跳过
     */
    private static final DefaultRedisScript<Long> RENAME_HOT_RANK_SCRIPT = new DefaultRedisScript<>(
            """
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return 0
            end
            redis.call('RENAME', KEYS[1], KEYS[2])
            redis.call('EXPIRE', KEYS[2], ARGV[1])
            return 1
            """,
            Long.class
    );

    private final UsersMapper usersMapper;
    private final CategoryMapper categoryMapper;
    private final ArticleLikesMapper articleLikesMapper;
    private final ArticleFavoritesMapper articleFavoritesMapper;
    private final CommentsMapper commentsMapper;
    private final ArticleRagSyncService articleRagSyncService;

    private final ObjectMapper objectMapper;

    private final StringRedisTemplate stringRedisTemplate;

    private final CacheTtlSupport cacheTtlSupport;

    private final RedisKeyScanner redisKeyScanner;

    private final UserSetCache userSetCache;
    private final AfterCommitExecutor afterCommitExecutor;
    private final UserActivityTracker userActivityTracker;

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
                            cacheTtlSupport.jitter(Duration.ofMinutes(RedisConstants.ARTICLE_LIST_TTL_MINUTES))
                    );
        }catch(Exception e){
            // 缓存失败不影响文章列表返回
        }
    }

    /** 文章详情缓存的读取结果（三态）——必须区分"空值缓存"与"未命中"，两者后续动作完全不同 */
    private enum CacheReadState { HIT, NULL_CACHED, MISS }

    private record DetailCacheRead(CacheReadState state, ArticleDetailVO value) {
        static DetailCacheRead hit(ArticleDetailVO value) {
            return new DetailCacheRead(CacheReadState.HIT, value);
        }

        static DetailCacheRead nullCached() {
            return new DetailCacheRead(CacheReadState.NULL_CACHED, null);
        }

        static DetailCacheRead miss() {
            return new DetailCacheRead(CacheReadState.MISS, null);
        }
    }

    @Override  //2.获取公开文章详情
    public ArticleDetailVO getPublicArticleById(Long id, String clientIp) {  //2.获取公开文章详情

        DetailCacheRead read = readArticleDetailCache(id);

        DetailCacheRead result = read.state() == CacheReadState.HIT
                ? read
                : loadArticleDetailWithMutex(id);

        if (result.state() != CacheReadState.HIT) {
            // NULL_CACHED = 文章不存在 / 当前身份不可见；MISS = 兜底（正常不会走到）
            return null;
        }

        ArticleDetailVO vo = result.value();

        Long userId = UserContext.get();

        // 活跃统计的对象是"人"，不是"阅读行为"——所以这一句不在下面的分支里：
        // 作者看自己的文章不计浏览量，但他本人**确实今天活跃了**。
        // 未登录时内部直接返回，游客没有 userId 可做 offset
        userActivityTracker.markActiveToday();

        if (userId == null || !userId.equals(vo.getAuthorId())) {
            recordArticleView(id, clientIp);
        }

        fillArticleLiked(vo);
        fillArticleFavorited(vo);
        fillArticleViewCount(List.of(vo));
        // UV 必须**在返回前现算**，不能进详情缓存：缓存 10 分钟内 UV 一直不动，
        // 而它恰恰是"现在有多少人正在看"这个感觉的来源。与 fillArticleViewCount 同一位置
        vo.setUvCount(readTodayUv(id));

        return vo;
    }

    /**
     * 文章详情缓存的**唯一**读入口（三态）。
     *
     * 收口的原因：原先读路径有两条（外层判空值 + getArticleDetailFromCache 内部再读一次），
     * 互斥回源的轮询就是第三条——三条对"空值 / 反序列化失败"的处理必须一致。否则轮询路径
     * 读到空值走反序列化，会触发"解析失败即删 key"，把别人刚写好的空值缓存误删，互斥自我破坏。
     *
     * 空值缓存的身份语义也收口在这里：不存在文章对所有身份都是空；隐藏 / 草稿等不可见文章
     * 会缓存作者与状态，只有"隐藏文章作者本人预览"才绕过空值回源，避免普通登录用户绕开空值缓存。
     */
    private DetailCacheRead readArticleDetailCache(Long id) {
        String key = RedisConstants.ARTICLE_DETAIL_KEY_PREFIX + id;
        String json;

        try {
            json = stringRedisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            // Redis 读取失败 → 当作未命中，交给 DB 路径（fail-open）
            return DetailCacheRead.miss();
        }

        if (json == null || json.isBlank()) {
            return DetailCacheRead.miss();
        }

        if (isArticleDetailNullCache(json)) {
            return readArticleDetailNullCache(id, json);
        }

        try {
            return DetailCacheRead.hit(objectMapper.readValue(json, ArticleDetailVO.class));
        } catch (JsonProcessingException e) {
            // 脏数据自愈：留着每次都会解析失败，删掉让下次走 DB 重建
            try {
                stringRedisTemplate.delete(key);
            } catch (Exception ignored) {
                // 删除失败不影响本次返回
            }
            return DetailCacheRead.miss();
        }
    }

    private boolean isArticleDetailNullCache(String json) {
        return RedisConstants.CACHE_NULL_VALUE.equals(json)
                || DETAIL_NULL_NOT_FOUND_VALUE.equals(json)
                || json.startsWith(DETAIL_NULL_INVISIBLE_PREFIX);
    }

    private DetailCacheRead readArticleDetailNullCache(Long id, String value) {
        if (DETAIL_NULL_NOT_FOUND_VALUE.equals(value)) {
            return DetailCacheRead.nullCached();
        }

        if (value.startsWith(DETAIL_NULL_INVISIBLE_PREFIX)) {
            String[] parts = value.substring(DETAIL_NULL_INVISIBLE_PREFIX.length()).split(":", 2);
            if (parts.length == 2) {
                try {
                    Long authorId = Long.valueOf(parts[0]);
                    Integer status = Integer.valueOf(parts[1]);
                    Long viewerId = UserContext.get();
                    if (Objects.equals(status, BlogConstants.ArticlesStatus.HIDDEN)
                            && viewerId != null
                            && viewerId.equals(authorId)) {
                        deleteArticleDetailCache(id);
                        return DetailCacheRead.miss();
                    }
                    return DetailCacheRead.nullCached();
                } catch (NumberFormatException ignored) {
                    // 脏空值缓存，删掉后回源自愈
                    deleteArticleDetailCache(id);
                    return DetailCacheRead.miss();
                }
            }
        }

        if (UserContext.get() == null) {
            return DetailCacheRead.nullCached();
        }
        // 兼容旧版 "__NULL__"：无法判断是否隐藏文章作者，只能回源一次并由新格式重写。
        deleteArticleDetailCache(id);
        return DetailCacheRead.miss();
    }

    /**
     * 互斥回源（防击穿）：同一篇文章只让一个请求查库重建，其余请求短轮询等待缓存被填好；
     * 轮询超时则 fail-open 自己查库——宁可退化，不让人等死。
     *
     * 注意：作者看自己的隐藏文章不会写共享缓存，因此该场景下轮询必然等到超时再回源——
     * 属于预期行为（低频场景），不是缺陷。
     */
    private DetailCacheRead loadArticleDetailWithMutex(Long id) {
        String lockKey = RedisConstants.CACHE_LOCK_ARTICLE_DETAIL_KEY_PREFIX + id;
        String token = UUID.randomUUID().toString();

        boolean locked;
        try {
            locked = Boolean.TRUE.equals(stringRedisTemplate.opsForValue()
                    .setIfAbsent(lockKey, token, DETAIL_LOCK_TTL_SECONDS, TimeUnit.SECONDS));
        } catch (Exception e) {
            // 锁不可用不能挡住用户看文章：直接回源
            return loadDetailFromDb(id);
        }

        if (!locked) {
            DetailCacheRead polled = pollArticleDetailCache(id);
            if (polled.state() != CacheReadState.MISS) {
                return polled;
            }
            // 轮询超时：持锁请求可能失败了，自己回源（fail-open）
            return loadDetailFromDb(id);
        }

        try {
            return loadDetailFromDb(id);
        } finally {
            releaseLock(lockKey, token);
        }
    }

    /**
     * 未抢到锁时的短轮询（50ms × 10 ≈ 500ms 上限）。
     * 必须走统一读入口——轮询路径若自己反序列化，会把空值缓存当脏数据删掉。
     */
    private DetailCacheRead pollArticleDetailCache(Long id) {
        for (int i = 0; i < DETAIL_LOCK_POLL_MAX_ATTEMPTS; i++) {
            try {
                Thread.sleep(DETAIL_LOCK_POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return DetailCacheRead.miss();
            }

            DetailCacheRead read = readArticleDetailCache(id);
            if (read.state() != CacheReadState.MISS) {
                // HIT = 别人填好了详情；NULL_CACHED = 持锁者确认了不可见（登录用户已在读入口转成 MISS）
                return read;
            }
        }
        return DetailCacheRead.miss();
    }

    /** 查 DB 并把结果转成读结果：共享缓存与空值缓存的写入已内聚在 getArticleDetailFromDb */
    private DetailCacheRead loadDetailFromDb(Long id) {
        ArticleDetailVO vo = getArticleDetailFromDb(id);
        return vo == null ? DetailCacheRead.nullCached() : DetailCacheRead.hit(vo);
    }

    /** 释放锁：Lua 校验 token，防止慢请求删掉别人的锁 */
    private void releaseLock(String lockKey, String token) {
        try {
            stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), token);
        } catch (Exception e) {
            // 释放失败由锁 TTL 兜底，不影响本次返回
        }
    }

    /** 浏览量 + 独立访客：一次浏览记两项统计。
     *  两项都是**可接受丢失的近似数据**，任何一项失败都不影响详情返回（外层统一兜底） */
    private void recordArticleView(Long id, String clientIp) {
        try {
            stringRedisTemplate.execute(
                    VIEW_INCR_SCRIPT,
                    Collections.singletonList(RedisConstants.ARTICLE_VIEW_KEY_PREFIX + id),
                    String.valueOf(TimeUnit.MINUTES.toSeconds(RedisConstants.ARTICLE_VIEW_TTL_MINUTES))
            );

            stringRedisTemplate.opsForZSet()
                    .incrementScore(RedisConstants.ARTICLE_HOT_KEY,
                            String.valueOf(id),
                            RedisConstants.ARTICLE_VIEW_HOT_SCORE);

            recordUniqueVisitor(id, clientIp);
        } catch (Exception e) {
            // Redis统计失败不影响文章详情返回
        }
    }

    /**
     * 记录独立访客（HyperLogLog）。
     *
     * **身份取法**：登录用户按 userId、游客按 IP，加前缀区分（`u:123` / `ip:1.2.3.4`）——
     * 不加前缀的话，"userId = 1 的用户"和"IP 恰好是 1"会被 HLL 当成同一个人。
     *
     * **口径本身是近似的**，两层误差叠加：
     * - 身份层：NAT 后面一群人算一个（偏低）、动态 IP 一个人算多个（偏高）
     * - 算法层：HyperLogLog 自身 **0.81% 标准误差**
     *
     * **UV 是趋势指标，不是精确值**。要精确就得存全量集合，而那个内存代价
     * （1 亿 UV 的 Set ≈ 800MB）正是这里用 HLL（固定 12KB）的原因。
     */
    private void recordUniqueVisitor(Long articleId, String clientIp) {
        Long userId = UserContext.get();
        String identity = userId != null
                ? "u:" + userId
                : (clientIp == null || clientIp.isBlank() ? null : "ip:" + clientIp);
        if (identity == null) {
            return;   // 两个身份都拿不到（理论上只在非 Web 线程发生），这次不计
        }

        stringRedisTemplate.execute(
                UV_ADD_SCRIPT,
                Collections.singletonList(buildUvKey(articleId)),
                identity,
                String.valueOf(TimeUnit.DAYS.toSeconds(RedisConstants.ARTICLE_UV_TTL_DAYS))
        );
    }

    /** UV key 按天分：article:uv:{articleId}:{yyyyMMdd}（与活跃统计的日期格式保持一致） */
    private String buildUvKey(Long articleId) {
        return RedisConstants.ARTICLE_UV_KEY_PREFIX + articleId + ":"
                + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    /** 读今日 UV。失败返回 0——UV 只是展示项，不能因为它让整个详情页失败 */
    private int readTodayUv(Long articleId) {
        try {
            Long size = stringRedisTemplate.opsForHyperLogLog().size(buildUvKey(articleId));
            return size == null ? 0 : size.intValue();
        } catch (Exception e) {
            return 0;
        }
    }


    @Override  //3.获取我自己的文章详情
    public ArticleDetailVO getArticlesById(Long id) {
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
        // 三态：null = 未加载或状态不可信（下面回源）；空 Set = 确认没点过赞；非空 = 命中
        Set<String> likedIdStrings = userSetCache.readIfLoaded(key);

        if(likedIdStrings == null){
            List<ArticleLikes> likes = articleLikesMapper.selectList(
                    new LambdaQueryWrapper<ArticleLikes>()
                            .eq(ArticleLikes::getUserId, currentUserId));

            likedIdStrings = likes.stream()
                    .map(like -> String.valueOf(like.getArticleId()))
                    .collect(Collectors.toSet());

            userSetCache.markLoaded(key, likedIdStrings);
        }

        Set<Long> likedArticleIds = likedIdStrings.stream()
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
        // 三态：null = 未加载或状态不可信（下面回源）；空 Set = 确认没收藏过；非空 = 命中
        Set<String> favoritedIdStrings = userSetCache.readIfLoaded(key);

        if(favoritedIdStrings == null){
            List<ArticleFavorites> favorites = articleFavoritesMapper.selectList(
                    new LambdaQueryWrapper<ArticleFavorites>()
                            .eq(ArticleFavorites::getUserId, currentUserId)
            );

            favoritedIdStrings = favorites.stream()
                    .map(favorite -> String.valueOf(favorite.getArticleId()))
                    .collect(Collectors.toSet());

            userSetCache.markLoaded(key, favoritedIdStrings);
        }

        Set<Long> favoritedArticleIds = favoritedIdStrings.stream()
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
            safelyIndexArticleRag(articles.getId());  //发布文章是自动同步RAG索引
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

        syncArticleRagIndex(articles);  //更新文章之后要判断文章的状态，是否隐藏
    }
    private void syncArticleRagIndex(Articles article){
        if (article == null || article.getId() == null) {
            return;
        }

        //发布状态 -》 写入/刷新RAG
        if(Objects.equals(article.getStatus(),BlogConstants.ArticlesStatus.PUBLISHED)){
            safelyIndexArticleRag(article.getId());
        }
        else{  //隐藏 -》 从RAG删除
            safelyDeleteArticleRagIndex(article.getId());
        }
    }


    /**
     * V3.4 Agent 受控写（UPDATE_ARTICLE_TITLE）专用：只改自己文章的标题。
     * 不走 updateArticle（全量 BeanUtil.copyProperties 会把 DTO 未传字段置 null）。
     * 归属校验用显式 userId（对齐学习域 service 模式，Agent 确认链路可测）。
     * 旧值条件更新 = 轻量 CAS：WHERE title = expectedOldTitle 影响 0 行 → 标题在提案后被并发修改
     * （如用户编辑器另存），本次改名基于的旧标题已失效 → 拒绝不覆盖，用户需重新发起提案。
     * （Articles 无 @Version；本方法标题条件更新是并发防护的最后一层，主窗口由 confirm 端 stale 校验拦截）
     */
    @Override
    @Transactional
    public void updateArticleTitle(Long id, String expectedOldTitle, String newTitle, Long userId) {
        Articles articles = getById(id);
        if (articles == null) {
            throw new IllegalArgumentException("未找到该博文");
        }
        if (!articles.getAuthorId().equals(userId)) {
            throw new IllegalArgumentException("无权操作该文章");
        }

        boolean updated = lambdaUpdate()
                .eq(Articles::getId, id)
                .eq(Articles::getAuthorId, userId)
                .eq(Articles::getTitle, expectedOldTitle)
                .set(Articles::getTitle, newTitle)
                .set(Articles::getUpdatedAt, LocalDateTime.now())
                .update();
        if (!updated) {
            throw new IllegalArgumentException("文章标题已变化，请重新发起修改");
        }

        deleteArticleDetailCache(id);
        deleteArticleListCache();
        syncArticleRagIndex(articles);  // 状态取自更新前行（改名不动状态）：已发布 → 重刷 RAG doc（含标题）
    }

    /**
     * V3.7 Agent 受控写（HIDE_ARTICLE / PUBLISH_ARTICLE）专用：原子条件状态更新。
     * 不直接复用 hideArticle/publishArticle（无条件 set status，confirm 前置校验到执行之间有 TOCTOU——
     * run 状态 CAS 只挡同 run 并发，挡不住文章被其他请求改状态）。
     * expectedStatus 前置进 WHERE（动作前置可从方向推导：HIDE 前置 PUBLISHED / PUBLISH 前置 HIDDEN），
     * 0 行 = 提案后状态已被并发修改 → 拒绝不覆盖。成功后按 targetStatus 对齐 hideArticle/publishArticle 副作用。
     */
    @Override
    @Transactional
    public void updateArticleVisibility(Long id, Integer expectedStatus, Integer targetStatus, Long userId) {
        Articles articles = getById(id);
        if (articles == null) {
            throw new IllegalArgumentException("未找到该博文");
        }
        if (!articles.getAuthorId().equals(userId)) {
            throw new IllegalArgumentException("无权操作该文章");
        }

        boolean updated;
        if (Objects.equals(targetStatus, BlogConstants.ArticlesStatus.PUBLISHED)) {
            // 公开：publishedAt=now（与编辑器「重新发布」一致——首次发布的语义时间被重置，属现有行为）
            updated = lambdaUpdate()
                    .eq(Articles::getId, id)
                    .eq(Articles::getAuthorId, userId)
                    .eq(Articles::getStatus, expectedStatus)
                    .set(Articles::getStatus, targetStatus)
                    .set(Articles::getPublishedAt, LocalDateTime.now())
                    .set(Articles::getUpdatedAt, LocalDateTime.now())
                    .update();
        } else {
            updated = lambdaUpdate()
                    .eq(Articles::getId, id)
                    .eq(Articles::getAuthorId, userId)
                    .eq(Articles::getStatus, expectedStatus)
                    .set(Articles::getStatus, targetStatus)
                    .set(Articles::getUpdatedAt, LocalDateTime.now())
                    .update();
        }
        if (!updated) {
            throw new IllegalArgumentException("文章状态已变化，请重新发起修改");
        }

        deleteArticleDetailCache(id);
        deleteArticleListCache();
        if (Objects.equals(targetStatus, BlogConstants.ArticlesStatus.PUBLISHED)) {
            safelyIndexArticleRag(id);      // 公开：建 RAG 索引（对齐 publishArticle）
        } else {
            safelyDeleteArticleRagIndex(id); // 隐藏：删 RAG 索引（对齐 hideArticle）
        }
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
        safelyDeleteArticleRagIndex(id);  //删除时自动同步 RAG 索引。
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

        safelyDeleteArticleRagIndex(id);  //隐藏时自动同步 RAG 索引。
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

        safelyIndexArticleRag(id);  //发布文章时自动同步 RAG 索引。

        // 发布是强活跃信号（比浏览更能说明"这个人在用这个站"），与浏览、评论一起构成活跃口径
        userActivityTracker.markActiveToday();
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


    @Override  //10.默认加载：他发布的文章
    public PageVO<ArticleDetailVO> getPublicUserArticles(Long id,Long page,Long pageSize) {
        Page<Articles> pageResult = lambdaQuery()
                .eq(Articles::getAuthorId,id)
                .eq(Articles::getStatus,BlogConstants.ArticlesStatus.PUBLISHED)
                .page(new Page<>(page,pageSize));

        List<ArticleDetailVO> list = pageResult.getRecords()
                .stream()
                .map(ArticleDetailVO::from)
                .toList();

        fillArticleMeta(list);
        fillArticleLiked(list);
        fillArticleFavorited(list);
        fillArticleViewCount(list);

        return new PageVO<>(
                list,
                pageResult.getTotal(),
                page,
                pageSize
        );
    }

    @Override  //11.查看他喜欢的文章
    public PageVO<ArticleDetailVO> getPublicUserLiked(Long id, Long page, Long pageSize) {

        Page<ArticleLikes> articleLikesPage = articleLikesMapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<ArticleLikes>()
                        .eq(ArticleLikes::getUserId, id)
                        .orderByDesc(ArticleLikes::getCreateTime));
        //拿到这一页的文章id
        List<Long> articleIds = articleLikesPage.getRecords().stream()
                .map(ArticleLikes::getArticleId)
                .toList();
        if (articleIds.isEmpty()) {
            return new PageVO<>(List.of(), 0L, page, pageSize);
        }
        //再查文章
        List<Articles> articles = lambdaQuery().in(Articles::getId, articleIds)
                .eq(Articles::getStatus, BlogConstants.ArticlesStatus.PUBLISHED)
                .list();
        //in 查出来的顺序不一定等于 articleIds 的顺序，所以要转 Map，再按 articleIds 顺序组装
        Map<Long,Articles> articleMap = articles.stream()
                .collect(Collectors.toMap(Articles::getId, Function.identity()));

        List<ArticleDetailVO> list = articleIds.stream()
                .map(articleMap::get)
                .map(ArticleDetailVO::from)
                .filter(Objects::nonNull)
                .toList();
        fillArticleMeta(list);
        fillArticleLiked(list);
        fillArticleFavorited(list);

        return new PageVO<>(
                list,
                articleLikesPage.getTotal(),
                page,
                pageSize
        );
    }

    @Override  //12.查看他收藏的文章
    public PageVO<ArticleDetailVO> getPublicUserFavorited(Long id, Long page, Long pageSize) {

        Page<ArticleFavorites> articleFavoritessPage = articleFavoritesMapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<ArticleFavorites>()
                        .eq(ArticleFavorites::getUserId, id)
                        .orderByDesc(ArticleFavorites::getCreateTime));
        //获取该页文章的id
        List<Long> articleIds = articleFavoritessPage.getRecords().stream()
                .map(ArticleFavorites::getArticleId)
                .toList();

        if (articleIds.isEmpty()) {
            return new PageVO<>(List.of(), 0L, page, pageSize);
        }
        //再查文章
        List<Articles> articles = lambdaQuery().in(Articles::getId, articleIds)
                .eq(Articles::getStatus, BlogConstants.ArticlesStatus.PUBLISHED)
                .list();

        Map<Long, Articles> articleMap = articles.stream()
                .collect(Collectors.toMap(Articles::getId, Function.identity()));

        List<ArticleDetailVO> list = articleIds.stream()
                .map(articleMap::get)
                .filter(Objects::nonNull)
                .map(ArticleDetailVO::from)
                .toList();

        fillArticleMeta(list);
        fillArticleLiked(list);
        fillArticleFavorited(list);

        return new PageVO<>(
                list,
                articleFavoritessPage.getTotal(),
                page,
                pageSize
        );

    }

    @Override  //13.查看他评论过的文章
    public PageVO<ArticleDetailVO> getPublicCommented(Long id, Long page, Long pageSize) {
        List<ArticleComments> comments = commentsMapper.selectList(
                new LambdaQueryWrapper<ArticleComments>()
                        .eq(ArticleComments::getUserId,id)
                        .orderByDesc(ArticleComments::getCreatedAt)
        );

        List<Long> articleIds = comments.stream()
                .map(ArticleComments::getArticleId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (articleIds.isEmpty()) {
            return new PageVO<>(List.of(), 0L, page, pageSize);
        }

        int from = (int) ((page - 1) * pageSize);
        int to = Math.min(from + pageSize.intValue(), articleIds.size());

        if (from >= articleIds.size()) {
            return new PageVO<>(List.of(), (long) articleIds.size(), page, pageSize);
        }

        List<Long> pageArticleIds = articleIds.subList(from, to);

        List<Articles> articles = lambdaQuery()
                .in(Articles::getId, pageArticleIds)
                .eq(Articles::getStatus, BlogConstants.ArticlesStatus.PUBLISHED)
                .list();

        Map<Long, Articles> articleMap = articles.stream()
                .collect(Collectors.toMap(Articles::getId, Function.identity()));

        List<ArticleDetailVO> list = pageArticleIds.stream()
                .map(articleMap::get)
                .filter(Objects::nonNull)
                .map(ArticleDetailVO::from)
                .toList();

        fillArticleMeta(list);
        fillArticleLiked(list);
        fillArticleFavorited(list);

        return new PageVO<>(
                list,
                (long) articleIds.size(),
                page,
                pageSize
        );
    }

    @Override  //14.转发文章
    public void shareArticle(Long id) {
        lambdaUpdate()
                .eq(Articles::getId,id)
                .setSql("share_count = share_count + 1")
                .update();

        deleteArticleDetailCache(id);
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
        // SCAN 游标迭代。原 KEYS 每 30 秒全库阻塞一次 Redis 服务端单线程
        List<String> keys = redisKeyScanner.scan(RedisConstants.ARTICLE_VIEW_KEY_PREFIX + "*");

        if(keys.isEmpty())
            return;

        int dropped = 0;
        for(String key : keys){
            // 先原子取走再更库：中途失败的方向是"丢一次增量"，而不是"key 没删掉→下次重复累加"。
            // SCAN 可能重复返回同一 key——重复时这里返回 null，天然幂等
            String incrementStr = takeViewIncrement(key);
            if(incrementStr == null)
                continue;

            try{
                int increment = Integer.valueOf(incrementStr);
                if(increment <= 0){
                    continue;
                }

                Long articleId = Long.valueOf(key.substring(RedisConstants.ARTICLE_VIEW_KEY_PREFIX.length()));

                lambdaUpdate()
                        .eq(Articles::getId,articleId)
                        .setSql("view_count = view_count + " + increment)
                        .update();
            }catch(Exception e){
                // 单个 key 失败不中断整轮（原先循环体内无容错，一次异常会让后面的 key 全部不同步）
                dropped++;
                log.warn("浏览量同步失败，本次增量丢弃: key={} value={}", key, incrementStr, e);
            }
        }

        if(dropped > 0){
            log.warn("浏览量同步完成，本轮丢弃 {} 个 key 的增量（浏览量是可接受丢失的近似统计）", dropped);
        }
    }

    /** 原子取走浏览量增量：GET + DEL 一次 Lua（不依赖 Redis 6.2+ 的 GETDEL 命令） */
    private String takeViewIncrement(String key){
        try{
            return stringRedisTemplate.execute(VIEW_TAKE_SCRIPT, Collections.singletonList(key));
        }catch(Exception e){
            log.warn("浏览量增量取走失败，跳过该 key: {}", key, e);
            return null;
        }
    }

    @Override  //重建热度榜
    public void rebuildArticleHotRank() {
        List<Articles> articles = lambdaQuery()
                .eq(Articles::getStatus, BlogConstants.ArticlesStatus.PUBLISHED)
                .list();

        //先建临时榜，建完原子替换。原实现是「先 delete 旧榜 → 再逐篇写入」，
        //中间窗口读热度榜会读到空榜
        String tmpKey = RedisConstants.ARTICLE_HOT_KEY + ":rebuilding";

        //清掉上一轮可能残留的临时榜（重建中途失败会留下）
        stringRedisTemplate.delete(tmpKey);

        if(articles.isEmpty()){
            //空榜边界：没有已发布文章时临时 key 不会被创建，不能 RENAME 一个不存在的 key
            stringRedisTemplate.delete(RedisConstants.ARTICLE_HOT_KEY);
            return;
        }

        // 批量取浏览量增量：原实现逐篇 GET，N 篇文章 N 次网络往返。
        // MGET 一次拿回全部，不存在的 key 在结果里对应 null（顺序与入参一一对应）
        List<String> viewKeys = articles.stream()
                .map(article -> RedisConstants.ARTICLE_VIEW_KEY_PREFIX + article.getId())
                .toList();
        List<String> viewCounts = stringRedisTemplate.opsForValue().multiGet(viewKeys);

        // 批量写临时榜：原实现逐篇 ZADD，又是 N 次往返。pipeline 把 N 条命令打包成一次往返。
        // 注意 pipeline **不保证原子性**（原子性是 Lua 的职责）——这里也不需要：
        // 临时榜建完才 RENAME，中途状态没有任何读者能看到。
        // 边界：文章数到万级时，MGET 结果与 pipeline 缓冲会一起堆在内存里，届时才需要分批
        stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (int i = 0; i < articles.size(); i++) {
                Articles article = articles.get(i);
                String redisViewCount = viewCounts == null ? null : viewCounts.get(i);
                int redisViewIncrement = redisViewCount == null ? 0 : Integer.parseInt(redisViewCount);

                double score =
                        safe(article.getViewCount()) * RedisConstants.ARTICLE_VIEW_HOT_SCORE
                                + redisViewIncrement * RedisConstants.ARTICLE_VIEW_HOT_SCORE
                                + safe(article.getLikeCount()) * RedisConstants.ARTICLE_LIKE_HOT_SCORE
                                + safe(article.getFavoriteCount()) * RedisConstants.ARTICLE_FAVORITE_HOT_SCORE
                                + safe(article.getCommentCount()) * RedisConstants.ARTICLE_COMMENT_HOT_SCORE;

                // pipeline 里拿到的是底层连接，不走模板的序列化器——key/value 需自行转字节。
                // StringRedisTemplate 用的 StringRedisSerializer 就是 UTF-8，编码与模板其余部分一致
                connection.zAdd(tmpKey.getBytes(StandardCharsets.UTF_8), score,
                        String.valueOf(article.getId()).getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });

        //检查 + RENAME + 补 TTL 打包成一次原子操作：
        //读方要么看到旧榜、要么看到新榜，不会看到空榜；并发重建也不会撞 RENAME 报错。
        //细节见 RENAME_HOT_RANK_SCRIPT 的注释
        Long replaced = stringRedisTemplate.execute(RENAME_HOT_RANK_SCRIPT,
                List.of(tmpKey, RedisConstants.ARTICLE_HOT_KEY),
                String.valueOf(TimeUnit.HOURS.toSeconds(RedisConstants.ARTICLE_HOT_TTL_HOURS)));

        if(replaced == null || replaced == 0){
            //临时 key 不在 = 已有另一个执行者完成了本轮替换。它写进去的数据和本轮同源，跳过即可
            log.info("热度榜临时 key 不存在，本轮替换跳过（可能已有并发重建完成）");
        }
    }

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }


    //拆一个“查文章基础详情”的方法
    //可见性规则（V3.7 修复：隐藏文章作者本人可看）：PUBLISHED → 任何人可见；
    //HIDDEN → 仅作者可见（共享缓存不写隐藏文章，防游客从缓存读到）；
    //DRAFT → 维持不可见（草稿预览走编辑器）；不存在/不可见 → 写空值缓存防穿透
    private ArticleDetailVO getArticleDetailFromDb(Long id){
        Articles articles = lambdaQuery()
                .eq(Articles::getId,id)
                .one();

        if(articles == null){
            writeNullDetailCache(id, DETAIL_NULL_NOT_FOUND_VALUE);
            return null;
        }

        Long viewerId = UserContext.get();
        boolean published = Objects.equals(articles.getStatus(), BlogConstants.ArticlesStatus.PUBLISHED);
        boolean hiddenOwner = Objects.equals(articles.getStatus(), BlogConstants.ArticlesStatus.HIDDEN)
                && viewerId != null && viewerId.equals(articles.getAuthorId());
        if(!published && !hiddenOwner){
            writeNullDetailCache(id, buildInvisibleNullCacheValue(articles));
            return null;
        }

        ArticleDetailVO vo = ArticleDetailVO.from(articles);

        fillArticleMeta(vo);

        if(published){
            saveArticleDetailToCache(id,vo);
        }

        return vo;
    }

    private String buildInvisibleNullCacheValue(Articles articles) {
        return DETAIL_NULL_INVISIBLE_PREFIX + articles.getAuthorId() + ":" + articles.getStatus();
    }

    /** 文章详情空值缓存（防穿透；游客/非作者访问隐藏文章、草稿、不存在时写） */
    private void writeNullDetailCache(Long id, String value){
        try{
            stringRedisTemplate.opsForValue().set(
                    RedisConstants.ARTICLE_DETAIL_KEY_PREFIX + id,
                    value,
                    cacheTtlSupport.jitter(Duration.ofMinutes(RedisConstants.CACHE_NULL_TTL_MINUTES))
            );
        }catch(Exception e){
            // 空值缓存写入失败不影响查询结果
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
                            cacheTtlSupport.jitter(Duration.ofMinutes(RedisConstants.ARTICLE_DETAIL_TTL_MINUTES))
                    );
        } catch(Exception e){
            //缓存失败不影响，后面可直接查询数据
        }
    }

    /**
     * 文章变更时删除详情缓存。走 {@link AfterCommitExecutor}：调用方里
     * `updateArticleTitle` / `updateArticleVisibility` 带 `@Transactional`，
     * 事务内删会留下"删完到提交之间读到旧值并回填"的窗口（详见该组件类注释）。
     */
    private void deleteArticleDetailCache(Long id) {
        String key = RedisConstants.ARTICLE_DETAIL_KEY_PREFIX + id;
        afterCommitExecutor.execute(() -> {
            try {
                stringRedisTemplate.delete(key);
            } catch (Exception e) {
                // 删不掉不影响文章变更本身，但必须留下可检索的痕迹——
                // 静默吞掉的话，缓存会一直脏到 TTL（详情 10 分钟），排查时无迹可寻
                log.warn("[CACHE-EVICT-FAIL] 文章详情缓存删除失败，将靠 TTL 兜底: key={}", key, e);
            }
        });
    }

    private void deleteArticleListCache() {
        // SCAN 游标迭代 + UNLINK 异步回收。原 KEYS 会阻塞 Redis 服务端单线程，
        // 连带卡住同实例上的限流、分布式锁与其它缓存。
        // SCAN 的失败日志在 RedisKeyScanner 内部
        afterCommitExecutor.execute(() ->
                redisKeyScanner.scanAndDelete(RedisConstants.ARTICLE_LIST_KEY_PREFIX + "*"));
    }

    //但如果 ES 没开、Embedding API 超时、额度没了，现在可能会导致“文章发布失败”
    //这不合理，最多影响AI检索，不影响用户正常发布文章
    private void safelyIndexArticleRag(Long articleId){
        try{
            articleRagSyncService.indexArticle(articleId);
        } catch (Exception e) {
            log.warn("文章 RAG 索引同步失败，articleId={}",articleId,e);
        }
    }
    private void safelyDeleteArticleRagIndex(Long articleId){
        try{
            articleRagSyncService.deleteArticleIndex(articleId);
        } catch (Exception e) {
            log.warn("文章 RAG 索引删除失败，articleId={}",articleId,e);
        }
    }
}
