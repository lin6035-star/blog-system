package com.hailin.blogsystem;

import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.service.ArticlesService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 热度榜重建（真 Redis）。
 *
 * **注意**：本测试会真的重建 `article:hot`——它读 DB 已发布文章重算，幂等，
 * 对开发环境无害（结果与 30 分钟定时任务重建一致）。
 *
 * **为什么值得自动化**（设计稿原计划只做手测）：TTL 必须补在 `RENAME` **之后**，
 * 因为 `RENAME` 会把目标 key 的 TTL 换成源 key（临时 key）的，而临时 key 每轮都是
 * 新建、无 TTL。这个顺序在重构时很容易被挪错，错了以后**症状是静默的**——榜单照常
 * 工作，只是永远不过期，`volatile-lru` 淘汰不到它。这种回归手测覆盖不了。
 */
@SpringBootTest
class ArticleHotRankTests {

    @Autowired
    private ArticlesService articlesService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void rebuildSetsTtlOnHotRank() {
        articlesService.rebuildArticleHotRank();

        Long card = stringRedisTemplate.opsForZSet().zCard(RedisConstants.ARTICLE_HOT_KEY);
        if (card == null || card == 0) {
            // 库里没有已发布文章 → 走空榜分支（DEL），没有榜可断言
            assertThat(stringRedisTemplate.hasKey(RedisConstants.ARTICLE_HOT_KEY)).isFalse();
            return;
        }

        Long ttl = stringRedisTemplate.getExpire(RedisConstants.ARTICLE_HOT_KEY, TimeUnit.SECONDS);
        assertThat(ttl).isNotNull().isGreaterThan(0);
    }

    @Test
    void rebuildLeavesNoTemporaryKey() {
        articlesService.rebuildArticleHotRank();

        // 临时 key 应已被 RENAME 消费掉；残留说明某轮重建中途失败过，下一轮会先清掉
        assertThat(stringRedisTemplate.hasKey(
                RedisConstants.ARTICLE_HOT_KEY + ":rebuilding")).isFalse();
    }

    @Test
    void concurrentRebuildDoesNotThrow() throws Exception {
        // 复现路径：定时任务启动首跑与手工 / 测试触发同时重建，两个执行者共享同一个临时 key，
        // 先完成的那个把它 RENAME 走，后一个就撞 `ERR no such key`——加 Lua 打包前这个错是必现的
        // （不是理论推演，是本测试类第一次跑的时候就炸出来的）。
        // 打包后后到者走 EXISTS 分支安静跳过：两个执行者的数据同源，跳过等于结果一样
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    articlesService.rebuildArticleHotRank();
                    return null;
                }));
            }
            start.countDown();

            for (Future<?> f : futures) {
                // 任一执行者抛异常 → ExecutionException → 测试失败
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * pipeline 改造的核心风险不是"写错"，是**命令根本没发出去**——`executePipelined`
     * 里的写法问题可能静默失败：榜单看起来"重建过了"，实际临时榜是空的，一 RENAME
     * 正式榜也跟着空掉。这个回归手测很难发现（要刚好在重建后去看榜单）。
     *
     * 用真实数据锁住"分数算得对 + 真的写进去了"，包括 Redis 里尚未同步的浏览增量。
     */
    @Test
    void rebuildWritesCorrectScores() {
        Articles article = new Articles();
        article.setTitle("hotrank_probe_" + System.nanoTime());
        article.setSummary("probe");
        article.setContent("probe content");
        article.setAuthorId(1L);
        article.setCategoryId(1L);
        article.setStatus(BlogConstants.ArticlesStatus.PUBLISHED);
        article.setViewCount(10);
        article.setLikeCount(2);
        article.setFavoriteCount(1);
        article.setCommentCount(3);
        article.setShareCount(0);
        article.setPublishedAt(LocalDateTime.now());
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());

        String viewKey = null;
        try {
            articlesService.save(article);
            Long id = article.getId();
            viewKey = RedisConstants.ARTICLE_VIEW_KEY_PREFIX + id;
            // Redis 里还压着 5 次未同步的浏览增量 —— MGET 必须把它取回来算进分数
            stringRedisTemplate.opsForValue().set(viewKey, "5");

            articlesService.rebuildArticleHotRank();

            // (10 + 5) × 1.0 浏览 + 2 × 2.0 点赞 + 1 × 3.0 收藏 + 3 × 4.0 评论 = 34
            Double score = stringRedisTemplate.opsForZSet()
                    .score(RedisConstants.ARTICLE_HOT_KEY, String.valueOf(id));

            assertThat(score)
                    .as("pipeline 写入的分数必须与逐条写入一致（含未同步的浏览增量）")
                    .isEqualTo(34.0);
        } finally {
            if (viewKey != null) {
                stringRedisTemplate.delete(viewKey);
            }
            if (article.getId() != null) {
                stringRedisTemplate.opsForZSet()
                        .remove(RedisConstants.ARTICLE_HOT_KEY, String.valueOf(article.getId()));
                articlesService.removeById(article.getId());
            }
        }
    }
}
