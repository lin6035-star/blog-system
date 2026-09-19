package com.hailin.blogsystem;

import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.vo.ArticleDetailVO;
import com.hailin.blogsystem.service.ArticlesService;
import com.hailin.blogsystem.task.ArticleViewCountSyncTask;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文章详情缓存**互斥回源**（防击穿）的行为验证（真 Redis + 真 MySQL）。
 *
 * **为什么必须是这一层**：互斥的价值全在"并发时序 + Redis 状态"里——
 * "另一个请求先抢到锁、我轮询等到它填好的缓存"这件事，Mockito 模拟不出来；
 * 拿 mock 去断言"查库几次"，断的是我自己写的桩，不是真实行为。
 *
 * **DB 查询次数怎么数**：自定义一个 MyBatis {@link Interceptor}（而不是 spy `ArticlesMapper`）。
 * 两条理由：① MyBatis 的 mapper 是 JDK 动态代理，Mockito spy 之后 `MybatisMapperProxy`
 * 的内部状态取不到（实测报 `Unable to retrieve the mapperInterface and sqlSession properties`）；
 * ② 数 SQL 语句是"真实发生了几次查库"的直接观测，比数某个 Java 方法的调用更贴近要证明的事。
 *
 * 详情路径上（游客身份）只有 `getArticleDetailFromDb` 会查 articles 表——
 * `fillArticleLiked` / `fillArticleFavorited` 在 `UserContext` 为空时直接返回，
 * `fillArticleMeta` 走 users 表、`fillArticleViewCount` 只读 Redis。
 */
@SpringBootTest
class ArticleDetailMutexTests {

    /** 探针文章标题前缀，跑完即删，不依赖也不污染库里的既有数据 */
    private static final String PROBE_TITLE_PREFIX = "mutex_probe_";

    @Autowired
    private ArticlesService articlesService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private SqlCounter sqlCounter;

    /**
     * 关掉定时任务：它每 30 秒把 Redis 里的浏览量取走写进 DB，
     * 会在 `viewIncrementIsTakenExactlyOnce` 中途把增量偷走——测的就不是"取删"而是"和定时任务赛跑"了。
     */
    @MockBean
    private ArticleViewCountSyncTask articleViewCountSyncTask;

    private Long probeId;
    private String detailKey;
    private String lockKey;
    private String viewKey;

    @BeforeEach
    void setUp() {
        Articles article = new Articles();
        article.setTitle(PROBE_TITLE_PREFIX + System.nanoTime());
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
        lockKey = RedisConstants.CACHE_LOCK_ARTICLE_DETAIL_KEY_PREFIX + probeId;
        viewKey = RedisConstants.ARTICLE_VIEW_KEY_PREFIX + probeId;

        cleanKeys();
        sqlCounter.reset();
    }

    @AfterEach
    void tearDown() {
        cleanKeys();
        if (probeId != null) {
            articlesService.removeById(probeId);
        }
    }

    /**
     * 互斥的全部价值：缓存失效瞬间，N 个并发请求只有 1 个进 DB。
     *
     * 用 latch 让 8 个线程同时起跑，制造真实的抢锁竞争——没有互斥的话这里是 8 次查询。
     */
    @Test
    void concurrentRequestsRebuildCacheOnlyOnce() throws Exception {
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<ArticleDetailVO> results = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    results.add(articlesService.getPublicArticleById(probeId, "9.9.9.9"));
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
            worker.setDaemon(true);
            worker.start();
        }

        start.countDown();
        assertThat(done.await(15, TimeUnit.SECONDS))
                .as("并发请求应在超时前全部返回（轮询上限 500ms，不该有人卡住）")
                .isTrue();

        assertThat(errors).isEmpty();
        assertThat(results).hasSize(threads).allSatisfy(vo -> {
            assertThat(vo).isNotNull();
            assertThat(vo.getId()).isEqualTo(probeId);
        });

        // 核心断言：只回源一次。没抢到锁的 7 个靠短轮询等到缓存被填好，没有各自进 DB
        assertThat(sqlCounter.articleSelects())
                .as("8 个并发请求只允许 1 次 DB 回源")
                .isEqualTo(1);
    }

    /**
     * 轮询读到空值缓存时必须**原样返回且不删 key**。
     *
     * 这是"轮询路径必须走统一三态读入口"的守卫：若轮询自己反序列化，
     * `__NULL__:not_found` 会触发"解析失败即删 key"的自愈逻辑，
     * 把持锁者刚写好的空值缓存删掉——互斥自我破坏，下一个请求又去查库。
     */
    @Test
    void pollingSeesNullCacheWithoutDeletingIt() throws Exception {
        // 模拟"另一个请求正持锁重建"：占住锁，它既不写共享缓存也不释放
        stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, "someone-else", 10, TimeUnit.SECONDS);

        // 150ms 后缓存里出现空值哨兵（= 持锁者判定"不存在 / 当前身份不可见"）
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(150);
                stringRedisTemplate.opsForValue().set(detailKey, RedisConstants.CACHE_NULL_VALUE + ":not_found");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        writer.setDaemon(true);
        writer.start();

        long startAt = System.currentTimeMillis();
        ArticleDetailVO vo = articlesService.getPublicArticleById(probeId, "9.9.9.9");
        long elapsed = System.currentTimeMillis() - startAt;
        writer.join();

        // 先断言这条测试的核心意图（不许误删），再断言返回值——
        // 顺序反过来的话，"误删"会先导致返回值错、红在返回值上，掩盖真正的病因。
        //
        // 断言的是**内容**而不是"key 存在"：误删之后轮询必然超时，超时又 fail-open 回源，
        // 回源会把正常的详情缓存写回去——只判空的话 key 又"存在"了，弱断言抓不住病因
        assertThat(stringRedisTemplate.opsForValue().get(detailKey))
                .as("轮询路径必须原样保留空值哨兵，不能当脏数据删掉后被详情缓存覆盖")
                .isEqualTo(RedisConstants.CACHE_NULL_VALUE + ":not_found");
        // 耗时下限把"主入口直接命中"这种假绿挡掉：直接命中只要几毫秒，
        // 走轮询至少要等过一个 50ms 的轮询间隔
        assertThat(elapsed)
                .as("必须确实走过了轮询路径，而不是主入口就命中了空值缓存")
                .isGreaterThanOrEqualTo(100L);
        assertThat(vo).isNull();
    }

    /**
     * 轮询超时必须 fail-open 自己回源——宁可退化，不让人等死。
     *
     * 构造：锁被一个"垮掉的"请求持有（不写缓存、不释放锁，只等它自己 TTL 过期）。
     */
    @Test
    void pollingTimeoutFallsBackToDatabase() {
        stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, "stuck-holder", 10, TimeUnit.SECONDS);

        long startAt = System.currentTimeMillis();
        ArticleDetailVO vo = articlesService.getPublicArticleById(probeId, "9.9.9.9");
        long elapsed = System.currentTimeMillis() - startAt;

        assertThat(vo).as("等不到缓存也要能返回文章").isNotNull();
        assertThat(vo.getId()).isEqualTo(probeId);
        assertThat(elapsed)
                .as("应等满轮询窗口（50ms × 10）再回源")
                .isGreaterThanOrEqualTo(400L);
        assertThat(sqlCounter.articleSelects()).as("超时后自己回源，恰好一次").isEqualTo(1);
    }

    /**
     * 浏览量增量**原子取走且只取一次**：`syncViewCountToDb` 跑第二遍时 key 已被取走，
     * 不该把同一批增量重复累加进 DB（SCAN 重复返回同一 key 时同样靠这个幂等）。
     */
    @Test
    void viewIncrementIsTakenExactlyOnce() {
        int before = articlesService.getById(probeId).getViewCount();

        // 三次浏览：第一次会走互斥回源，后两次命中缓存，但每次都记浏览量
        articlesService.getPublicArticleById(probeId, "9.9.9.9");
        articlesService.getPublicArticleById(probeId, "9.9.9.9");
        articlesService.getPublicArticleById(probeId, "9.9.9.9");
        assertThat(stringRedisTemplate.opsForValue().get(viewKey)).isEqualTo("3");

        articlesService.syncViewCountToDb();
        assertThat(articlesService.getById(probeId).getViewCount())
                .as("增量应被累加进 DB")
                .isEqualTo(before + 3);
        assertThat(stringRedisTemplate.opsForValue().get(viewKey))
                .as("取走后 key 必须消失——否则下一轮会重复累加")
                .isNull();

        // 再同步一次：没有残留增量，DB 不该再变
        articlesService.syncViewCountToDb();
        assertThat(articlesService.getById(probeId).getViewCount())
                .as("重复同步不能把同一批增量加第二次")
                .isEqualTo(before + 3);
    }

    private void cleanKeys() {
        if (detailKey == null) {
            // setUp 建文章失败时 tearDown 仍会被调用，此时没有任何 key 可清
            return;
        }
        stringRedisTemplate.delete(detailKey);
        stringRedisTemplate.delete(lockKey);
        stringRedisTemplate.delete(viewKey);
        // 详情读取顺带会记 UV，用例跑完要一并清掉，别给开发环境的 Redis 留垃圾
        stringRedisTemplate.delete(RedisConstants.ARTICLE_UV_KEY_PREFIX + probeId + ":"
                + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE));
    }

    /** 记录执行过的 SQL 语句 id，用来数"真实查库次数" */
    static class SqlCounter {
        private final List<String> statements = Collections.synchronizedList(new ArrayList<>());

        void record(String statementId) {
            statements.add(statementId);
        }

        void reset() {
            statements.clear();
        }

        long articleSelects() {
            return statements.stream()
                    .filter(id -> id.contains("ArticlesMapper") && id.contains("select"))
                    .count();
        }
    }

    /**
     * 挂在 SqlSessionFactory 上的计数拦截器——Spring Boot 会把容器里所有
     * `org.apache.ibatis.plugin.Interceptor` bean 自动注册为 MyBatis 插件。
     *
     * `@Intercepts` 不是可选的：MyBatis 从它读签名来决定拦哪些方法（不声明的话
     * `Plugin.wrap` 直接抛 `No @Intercepts annotation was found`）。
     * 这里只声明 `query`——本次要数的就是"查了几次"，写操作不拦。
     */
    @Intercepts({
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                            CacheKey.class, BoundSql.class})
    })
    static class CountingInterceptor implements Interceptor {
        private final SqlCounter counter;

        CountingInterceptor(SqlCounter counter) {
            this.counter = counter;
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            counter.record(((MappedStatement) invocation.getArgs()[0]).getId());
            return invocation.proceed();
        }

        @Override
        public Object plugin(Object target) {
            return Plugin.wrap(target, this);
        }

        @Override
        public void setProperties(Properties properties) {
            // 无配置项
        }
    }

    @TestConfiguration
    static class SqlCountingConfig {
        @Bean
        SqlCounter sqlCounter() {
            return new SqlCounter();
        }

        @Bean
        Interceptor articleSqlCountingInterceptor(SqlCounter sqlCounter) {
            return new CountingInterceptor(sqlCounter);
        }
    }
}
