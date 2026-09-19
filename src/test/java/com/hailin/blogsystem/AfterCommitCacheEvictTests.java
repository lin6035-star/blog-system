package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.rag.ArticleRagSyncService;
import com.hailin.blogsystem.component.AfterCommitExecutor;
import com.hailin.blogsystem.component.UserSetCache;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.service.ArticlesService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 缓存失效时机：**提交后才删**（第 5 刀）。
 *
 * **要证的两件事**（改造前都不成立）：
 * 1. 事务方法里删缓存，删除动作不能早于事务提交——早删会留下"删完到提交之间读到旧值并回填"的窗口
 * 2. 事务回滚时不该动缓存——数据没变，删了纯属白删（下一批读请求还要多回源一次）
 *
 * 用 {@link TransactionTemplate} 手动控制事务边界，是为了能在**事务内**断言一次、**提交后**再断言一次。
 * 测试方法本身不能带 `@Transactional`——外层事务会把被测方法的事务一起包住，`afterCommit` 永不触发。
 */
@SpringBootTest
class AfterCommitCacheEvictTests {

    private static final String PROBE_TITLE_PREFIX = "aftercommit_probe_";

    @Autowired
    private ArticlesService articlesService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private AfterCommitExecutor afterCommitExecutor;

    @Autowired
    private UserSetCache userSetCache;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 索引同步是 @Async + 真实 ES：测的是缓存时机，不该被它拖下水 */
    @MockBean
    private ArticleRagSyncService articleRagSyncService;

    private TransactionTemplate transactionTemplate;

    private Long probeId;
    private String probeTitle;
    private String detailKey;
    private String userSetKey;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);

        Articles article = new Articles();
        probeTitle = PROBE_TITLE_PREFIX + System.nanoTime();
        article.setTitle(probeTitle);
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

        probeId = article.getId();
        detailKey = RedisConstants.ARTICLE_DETAIL_KEY_PREFIX + probeId;
        userSetKey = RedisConstants.ARTICLE_LIKED_USER_KEY_PREFIX + "aftercommit_probe_user";

        stringRedisTemplate.delete(detailKey);
        stringRedisTemplate.delete(userSetKey);
        stringRedisTemplate.delete(userSetKey + RedisConstants.USER_SET_LOADED_SUFFIX);
    }

    @AfterEach
    void tearDown() {
        if (detailKey != null) {
            stringRedisTemplate.delete(detailKey);
            stringRedisTemplate.delete(userSetKey);
            stringRedisTemplate.delete(userSetKey + RedisConstants.USER_SET_LOADED_SUFFIX);
        }
        if (probeId != null) {
            articlesService.removeById(probeId);
        }
    }

    /**
     * 端到端版：真实的 `@Transactional` 方法（`updateArticleTitle`）+ 真实的缓存 key。
     *
     * 改造前这里第一条断言就会红——删除动作发生在事务里，缓存当场消失。
     */
    @Test
    void evictsDetailCacheOnlyAfterCommit() {
        stringRedisTemplate.opsForValue().set(detailKey, "{\"id\":1}");
        String newTitle = probeTitle + "_renamed";

        transactionTemplate.executeWithoutResult(status -> {
            articlesService.updateArticleTitle(probeId, probeTitle, newTitle, 1L);

            // 事务尚未提交：此时删缓存的话，任何一次 miss 都会读到"还没提交的旧标题"并回填
            assertThat(stringRedisTemplate.opsForValue().get(detailKey))
                    .as("提交之前不能删缓存")
                    .isNotNull();
        });

        assertThat(stringRedisTemplate.opsForValue().get(detailKey))
                .as("提交之后必须删掉，让下一次读回源拿到新标题")
                .isNull();
        assertThat(articlesService.getById(probeId).getTitle()).isEqualTo(newTitle);
    }

    /**
     * 回滚路径：数据没变，缓存就该原封不动。
     *
     * 改造前会白删——虽然不影响正确性（下次读回源还是同样的值），但平白多一次 DB 回源，
     * 且掩盖了"事务其实没成功"这个事实。
     */
    @Test
    void rollbackLeavesDetailCacheIntact() {
        stringRedisTemplate.opsForValue().set(detailKey, "{\"id\":1}");

        transactionTemplate.executeWithoutResult(status -> {
            articlesService.updateArticleTitle(probeId, probeTitle, probeTitle + "_discarded", 1L);
            status.setRollbackOnly();
        });

        assertThat(stringRedisTemplate.opsForValue().get(detailKey))
                .as("回滚后缓存不该被删：数据没变，删了只会让下一批请求白回源")
                .isNotNull();
        assertThat(articlesService.getById(probeId).getTitle()).isEqualTo(probeTitle);
    }

    /** 无事务调用方仍是立即执行——这是 fallback 不变量，改造不能把它们的缓存失效弄丢 */
    @Test
    void executesImmediatelyWithoutTransaction() {
        AtomicBoolean ran = new AtomicBoolean(false);

        afterCommitExecutor.execute(() -> ran.set(true));

        assertThat(ran).as("没有事务时就地执行，行为与改造前一致").isTrue();
    }

    /** 事务内只登记不执行，提交时才跑 */
    @Test
    void defersActionUntilCommit() {
        AtomicBoolean ran = new AtomicBoolean(false);

        transactionTemplate.executeWithoutResult(status -> {
            afterCommitExecutor.execute(() -> ran.set(true));
            assertThat(ran).as("事务内不能执行").isFalse();
        });

        assertThat(ran).as("提交后才执行").isTrue();
    }

    /**
     * 提交后动作抛异常**不能传播出去**：事务已经提交成功，让接口回一个失败
     * 比缓存脏更难排查（用户以为没成功、其实库里已经改了）。
     */
    @Test
    void failingActionDoesNotBreakCommit() {
        assertThatCode(() -> transactionTemplate.executeWithoutResult(status ->
                afterCommitExecutor.execute(() -> {
                    throw new IllegalStateException("boom");
                })
        )).doesNotThrowAnyException();
    }

    /** 用户集合缓存（点赞 / 收藏）的维护同样延后到提交后——它也是"事务内不许动的副作用" */
    @Test
    void userSetMaintenanceIsDeferredToo() {
        userSetCache.markLoaded(userSetKey, Set.of("1001", "1002"));

        transactionTemplate.executeWithoutResult(status -> {
            userSetCache.removeIfLoaded(userSetKey, "1001");
            assertThat(stringRedisTemplate.opsForSet().members(userSetKey))
                    .as("提交之前集合不能变")
                    .containsExactlyInAnyOrder("1001", "1002");
        });

        assertThat(stringRedisTemplate.opsForSet().members(userSetKey))
                .as("提交之后才移除")
                .containsExactly("1002");
    }
}
