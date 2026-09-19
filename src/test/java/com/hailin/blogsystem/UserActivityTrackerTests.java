package com.hailin.blogsystem;

import com.hailin.blogsystem.component.RedisKeyScanner;
import com.hailin.blogsystem.component.UserActivityTracker;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户日活跃位图（Bitmap，第三刀）。
 *
 * **所有用例都用 2000-01-01 这类历史日期**，不碰"今天"的 key——
 * 否则测试会污染真实的活跃统计（同一天是同一个 key，断言和真实数据会互相干扰）。
 *
 * 重点覆盖两类东西：
 * 1. **位图语义**：同一天重复置位只算一个、跨天 OR 要去重、AND 求留存
 * 2. **offset 防线**：userId 越界必须静默丢弃。真换了雪花 ID，
 *    一次 SETBIT 就能让 Redis 试图分配天文数字的内存——这是不可逆的事故
 */
@SpringBootTest
class UserActivityTrackerTests {

    /** 测试基准日：故意选一个远古日期，与真实统计完全隔离 */
    private static final LocalDate BASE_DATE = LocalDate.of(2000, 1, 1);

    @Autowired
    private UserActivityTracker userActivityTracker;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisKeyScanner redisKeyScanner;

    @BeforeEach
    void setUp() {
        UserContext.clear();
        cleanBaseDates();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        cleanBaseDates();
        cleanTemporaryKeys();
    }

    @Test
    void sameUserSameDayCountsOnce() {
        userActivityTracker.markActive(1001L, BASE_DATE);
        userActivityTracker.markActive(1001L, BASE_DATE);
        userActivityTracker.markActive(1001L, BASE_DATE);

        assertThat(userActivityTracker.countActive(BASE_DATE))
                .as("同一位重复置 1 仍然只是一个活跃用户")
                .isEqualTo(1);
    }

    @Test
    void differentUsersCountSeparately() {
        userActivityTracker.markActive(1001L, BASE_DATE);
        userActivityTracker.markActive(1002L, BASE_DATE);
        userActivityTracker.markActive(1003L, BASE_DATE);

        assertThat(userActivityTracker.countActive(BASE_DATE)).isEqualTo(3);
    }

    @Test
    void activeKeyCarriesTtl() {
        userActivityTracker.markActive(1001L, BASE_DATE);

        Long ttl = stringRedisTemplate.getExpire(keyOf(BASE_DATE), TimeUnit.SECONDS);
        assertThat(ttl)
                .as("活跃位图也是「允许丢失」的统计数据，必须带 TTL")
                .isNotNull()
                .isGreaterThan(0);
    }

    /**
     * offset 越界防线。1 亿的 offset 意味着 Redis 要为这个 key 分配 12.5MB，
     * 而雪花 ID 量级的 offset 直接是天文数字——必须静默丢弃。
     */
    @Test
    void oversizedUserIdIsNotWritten() {
        userActivityTracker.markActive(100_000_000L, BASE_DATE);

        assertThat(stringRedisTemplate.hasKey(keyOf(BASE_DATE)))
                .as("越界 ID 不能建出 key，否则位图会按这个 offset 分配内存")
                .isFalse();
    }

    @Test
    void nonPositiveUserIdIsNotWritten() {
        userActivityTracker.markActive(0L, BASE_DATE);
        userActivityTracker.markActive(-5L, BASE_DATE);

        assertThat(stringRedisTemplate.hasKey(keyOf(BASE_DATE))).isFalse();
    }

    /** 未登录时 markActiveToday 什么都不做——游客没有 userId 可做 offset */
    @Test
    void markActiveTodayDoesNothingWhenNotLoggedIn() {
        LocalDate today = LocalDate.now();
        long before = userActivityTracker.countActive(today);

        UserContext.clear();
        userActivityTracker.markActiveToday();

        assertThat(userActivityTracker.countActive(today))
                .as("未登录不该写入任何位")
                .isEqualTo(before);
    }

    /** 区间统计是**去重后的总人数**，不是每天相加——OR 天然去重，这是位图相对 Set 的额外好处 */
    @Test
    void rangeCountDeduplicatesAcrossDays() {
        LocalDate day1 = BASE_DATE;
        LocalDate day2 = BASE_DATE.plusDays(1);
        LocalDate day3 = BASE_DATE.plusDays(2);

        userActivityTracker.markActive(1001L, day1);
        userActivityTracker.markActive(1002L, day1);
        userActivityTracker.markActive(1001L, day2);   // 1001 两天都活跃
        userActivityTracker.markActive(1003L, day3);

        assertThat(userActivityTracker.countActiveBetween(day1, day3))
                .as("三天一共 3 个人（1001 / 1002 / 1003），1001 出现两次只能算一个")
                .isEqualTo(3);
    }

    /** 留存 = 两天都活跃的人数（AND） */
    @Test
    void retentionCountsUsersActiveOnBothDays() {
        LocalDate day1 = BASE_DATE;
        LocalDate day2 = BASE_DATE.plusDays(1);

        userActivityTracker.markActive(1001L, day1);
        userActivityTracker.markActive(1002L, day1);
        userActivityTracker.markActive(1001L, day2);   // 只有 1001 回来了
        userActivityTracker.markActive(1003L, day2);   // 新用户，不算留存

        assertThat(userActivityTracker.countRetained(day1, day2))
                .as("只有 1001 两天都活跃")
                .isEqualTo(1);
    }

    /** BITOP 的临时 key 必须用完即删——否则每调一次统计就多一个孤儿 key */
    @Test
    void bitOpLeavesNoTemporaryKey() {
        userActivityTracker.markActive(1001L, BASE_DATE);
        userActivityTracker.markActive(1001L, BASE_DATE.plusDays(1));

        userActivityTracker.countRetained(BASE_DATE, BASE_DATE.plusDays(1));

        assertThat(redisKeyScanner.scan(RedisConstants.USER_ACTIVE_KEY_PREFIX + "tmp:*"))
                .as("BITOP 结果用的临时 key 不能留下来")
                .isEmpty();
    }

    private String keyOf(LocalDate date) {
        return RedisConstants.USER_ACTIVE_KEY_PREFIX
                + date.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    private void cleanBaseDates() {
        for (int i = 0; i < 3; i++) {
            stringRedisTemplate.delete(keyOf(BASE_DATE.plusDays(i)));
        }
    }

    /**
     * 清掉 BITOP 可能留下的临时 key。
     *
     * 正常情况下方法自己在 finally 里删了，这里是**双保险 + 清历史遗留**：
     * 反向验证故意制造过孤儿（那时清理被注释掉了），不扫一遍就会让
     * {@link #bitOpLeavesNoTemporaryKey} 在后续运行里误报。
     *
     * 注意这不削弱那个用例——它断言在 tearDown **之前**执行，
     * 所以真出现孤儿时它仍然会红。
     */
    private void cleanTemporaryKeys() {
        for (String key : redisKeyScanner.scan(RedisConstants.USER_ACTIVE_KEY_PREFIX + "tmp:*")) {
            stringRedisTemplate.delete(key);
        }
    }
}
