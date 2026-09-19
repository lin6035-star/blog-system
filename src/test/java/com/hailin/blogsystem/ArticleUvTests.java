package com.hailin.blogsystem;

import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.service.ArticlesService;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文章独立访客（HyperLogLog，第二刀）。
 *
 * **要证的四件事**：
 * 1. 同一身份重复访问只算一个——这是 UV 和 PV 的本质区别（HLL 的去重语义）
 * 2. **身份前缀真的把登录用户和游客分开了**——不加 `u:` / `ip:` 前缀的话，
 *    "userId = 999 的用户"和"IP 恰好是 999"会被 HLL 当成同一个人
 * 3. UV key 带 TTL——它和浏览量的 Lua 同构，理由也一样：「允许丢失的数据都有 TTL」
 *    是 volatile-lru 能正常淘汰的前提
 * 4. 作者本人不计入访客——与浏览量的口径保持一致（两处不一致就是 bug 温床）
 */
@SpringBootTest
class ArticleUvTests {

    @Autowired
    private ArticlesService articlesService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private Articles article;
    private String uvKey;

    @BeforeEach
    void setUp() {
        UserContext.clear();

        article = new Articles();
        article.setTitle("uv_probe_" + System.nanoTime());
        article.setSummary("probe");
        article.setContent("probe content");
        article.setAuthorId(1L);
        article.setCategoryId(1L);
        article.setStatus(BlogConstants.ArticlesStatus.PUBLISHED);
        article.setViewCount(0);
        article.setLikeCount(0);
        article.setFavoriteCount(0);
        article.setCommentCount(0);
        article.setShareCount(0);
        article.setPublishedAt(LocalDateTime.now());
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        articlesService.save(article);

        uvKey = uvKey(article.getId());
        cleanProbeKeys();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        if (article != null && article.getId() != null) {
            cleanProbeKeys();
            articlesService.removeById(article.getId());
        }
    }

    /** 同一身份访问三次仍然只算一个访客——PV 却要照常累加到 3，两者不能互相影响 */
    @Test
    void repeatedVisitsFromSameIdentityCountOnce() {
        for (int i = 0; i < 3; i++) {
            articlesService.getPublicArticleById(article.getId(), "1.2.3.4");
        }

        assertThat(uvCount())
                .as("同一 IP 访问三次 = 1 个独立访客")
                .isEqualTo(1);
        assertThat(stringRedisTemplate.opsForValue()
                .get(RedisConstants.ARTICLE_VIEW_KEY_PREFIX + article.getId()))
                .as("PV 照常累加，不受 UV 去重影响")
                .isEqualTo("3");
    }

    /** 不同 IP 各算一个 */
    @Test
    void differentIdentitiesCountSeparately() {
        articlesService.getPublicArticleById(article.getId(), "1.1.1.1");
        articlesService.getPublicArticleById(article.getId(), "2.2.2.2");

        assertThat(uvCount())
                .as("两个不同 IP = 2 个独立访客")
                .isEqualTo(2);
    }

    /**
     * 身份前缀的作用：**故意让 userId 与 IP 的字面量完全相同**——一旦前缀被"简化"掉，
     * 这两个身份就会在 HLL 里撞成一个访客，而这正是加前缀要防的事。
     *
     * （IP 写成纯数字只是为了构造撞车；真实调用里 `ClientIpUtils` 给的是点分格式，
     * 但它返回什么这里并不关心——本用例直接把字符串传进 Service。）
     */
    @Test
    void loggedInUserAndGuestAreDistinctIdentities() {
        UserContext.set(12345L);
        try {
            articlesService.getPublicArticleById(article.getId(), "12345");
        } finally {
            UserContext.clear();
        }

        articlesService.getPublicArticleById(article.getId(), "12345");

        assertThat(uvCount())
                .as("u:12345 与 ip:12345 是两个身份，去掉前缀就会撞成一个")
                .isEqualTo(2);
    }

    /** UV key 必须带 TTL——它也是"允许丢失"的数据，无 TTL 就破坏了 volatile-lru 的淘汰前提 */
    @Test
    void uvKeyCarriesTtl() {
        articlesService.getPublicArticleById(article.getId(), "1.2.3.4");

        Long ttl = stringRedisTemplate.getExpire(uvKey, TimeUnit.SECONDS);
        assertThat(ttl)
                .as("UV key 必须带兜底 TTL，不能出现永不过期的统计 key")
                .isNotNull()
                .isGreaterThan(0);
    }

    /** 作者看自己的文章不算访客——与浏览量的口径保持一致 */
    @Test
    void authorSelfViewIsNotCounted() {
        UserContext.set(article.getAuthorId());
        try {
            articlesService.getPublicArticleById(article.getId(), "1.2.3.4");
        } finally {
            UserContext.clear();
        }

        assertThat(uvCount())
                .as("作者本人浏览不计入访客，和浏览量口径一致")
                .isZero();
    }

    private long uvCount() {
        Long size = stringRedisTemplate.opsForHyperLogLog().size(uvKey);
        return size == null ? 0 : size;
    }

    private void cleanProbeKeys() {
        stringRedisTemplate.delete(uvKey);
        stringRedisTemplate.delete(RedisConstants.ARTICLE_VIEW_KEY_PREFIX + article.getId());
        stringRedisTemplate.delete(RedisConstants.ARTICLE_DETAIL_KEY_PREFIX + article.getId());
    }

    private static String uvKey(Long articleId) {
        return RedisConstants.ARTICLE_UV_KEY_PREFIX + articleId + ":"
                + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    }
}
