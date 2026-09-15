package com.hailin.blogsystem;

import com.hailin.blogsystem.component.UserSetCache;
import com.hailin.blogsystem.constants.RedisConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户集合缓存的状态机（真 Redis）。
 *
 * **为什么必须连真 Redis**：这次的核心逻辑全在 Lua 脚本里——「何时才算全量」
 * 「取消最后一项怎么转换状态」这些判断用 mock 测不到。
 *
 * 用例对应设计稿 §5.5 的状态转换表：
 * <pre>
 * 未加载               -> 回源 DB -> FULL / EMPTY
 * FULL + 点赞          -> Set SADD + 刷新 Set TTL，保持 FULL
 * FULL + 取消最后一项   -> 切换为 EMPTY，不能留下"FULL + 缺 Set"
 * EMPTY + 新增一项      -> 创建 Set，状态改为 FULL
 * loaded=FULL 但 Set 丢失 -> 回源 DB 修复，不能直接返回空集合
 * </pre>
 */
@SpringBootTest
class UserSetCacheIntegrationTests {

    private static final String SET_KEY = "test:user:set:liked:1";
    private static final String LOADED_KEY = SET_KEY + RedisConstants.USER_SET_LOADED_SUFFIX;

    @Autowired
    private UserSetCache userSetCache;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    @AfterEach
    void cleanKeys() {
        stringRedisTemplate.delete(SET_KEY);
        stringRedisTemplate.delete(LOADED_KEY);
    }

    // ---------- 读取：三态 ----------

    @Test
    void readReturnsNullWhenNotLoaded() {
        // 没有任何标记 = 未加载，调用方必须回源
        assertThat(userSetCache.readIfLoaded(SET_KEY)).isNull();
    }

    @Test
    void readReturnsMembersWhenFullAndSetPresent() {
        userSetCache.markLoaded(SET_KEY, Set.of("1", "2", "3"));

        assertThat(userSetCache.readIfLoaded(SET_KEY)).containsExactlyInAnyOrder("1", "2", "3");
    }

    @Test
    void readReturnsEmptySetWhenStateIsEmpty() {
        // 查过了、用户确实没有数据 —— 这是「命中空集合」，不是「未加载」
        userSetCache.markLoaded(SET_KEY, Set.of());

        assertThat(userSetCache.readIfLoaded(SET_KEY)).isEmpty();
        assertThat(stringRedisTemplate.opsForValue().get(LOADED_KEY)).isEqualTo("EMPTY");
        assertThat(stringRedisTemplate.hasKey(SET_KEY)).isFalse();
    }

    @Test
    void readReturnsNullWhenFullButSetMissing() {
        // 核心修复点：标记说全量、集合却不在（内存淘汰 / 误删）
        // 必须按未加载处理回源，不能返回空集合——那等于告诉用户"你没点过赞"
        userSetCache.markLoaded(SET_KEY, Set.of("1", "2"));
        stringRedisTemplate.delete(SET_KEY);

        assertThat(userSetCache.readIfLoaded(SET_KEY)).isNull();
    }

    @Test
    void readTreatsLegacyFlagAsFull() {
        // 迁移期旧值 "1" + 集合存在 -> 按 FULL 用
        stringRedisTemplate.opsForSet().add(SET_KEY, "7");
        stringRedisTemplate.opsForValue().set(LOADED_KEY, "1", 30, TimeUnit.MINUTES);

        assertThat(userSetCache.readIfLoaded(SET_KEY)).containsExactly("7");
    }

    @Test
    void readReturnsNullForLegacyFlagWhenSetMissing() {
        // 旧值 "1" 但集合丢了：历史数据不可信，必须回源而不是当成空集合
        stringRedisTemplate.opsForValue().set(LOADED_KEY, "1", 30, TimeUnit.MINUTES);

        assertThat(userSetCache.readIfLoaded(SET_KEY)).isNull();
    }

    // ---------- 写入：仅在已加载时维护 ----------

    @Test
    void addDoesNothingWhenNotLoaded() {
        userSetCache.addIfLoaded(SET_KEY, "5");

        // 关键：不创建集合、也不写标记。
        // 写标记会让"只有这一条记录的集合"自称全量，历史点赞就再也显示不出来了
        assertThat(stringRedisTemplate.hasKey(SET_KEY)).isFalse();
        assertThat(stringRedisTemplate.hasKey(LOADED_KEY)).isFalse();
    }

    @Test
    void removeDoesNothingWhenNotLoaded() {
        userSetCache.removeIfLoaded(SET_KEY, "5");

        assertThat(stringRedisTemplate.hasKey(SET_KEY)).isFalse();
        assertThat(stringRedisTemplate.hasKey(LOADED_KEY)).isFalse();
    }

    @Test
    void addKeepsFullStateAndRefreshesTtl() {
        userSetCache.markLoaded(SET_KEY, Set.of("1"));

        userSetCache.addIfLoaded(SET_KEY, "2");

        assertThat(userSetCache.readIfLoaded(SET_KEY)).containsExactlyInAnyOrder("1", "2");
        assertThat(stringRedisTemplate.opsForValue().get(LOADED_KEY)).isEqualTo("FULL");
    }

    @Test
    void removingLastElementSwitchesToEmpty() {
        // 核心状态转换：不能留下「FULL + 缺集合」，否则读路径会判成异常、白白多一次回源
        userSetCache.markLoaded(SET_KEY, Set.of("1"));

        userSetCache.removeIfLoaded(SET_KEY, "1");

        assertThat(stringRedisTemplate.opsForValue().get(LOADED_KEY)).isEqualTo("EMPTY");
        assertThat(stringRedisTemplate.hasKey(SET_KEY)).isFalse();
        assertThat(userSetCache.readIfLoaded(SET_KEY)).isEmpty();
    }

    @Test
    void removingOneOfManyKeepsFull() {
        userSetCache.markLoaded(SET_KEY, Set.of("1", "2"));

        userSetCache.removeIfLoaded(SET_KEY, "1");

        assertThat(stringRedisTemplate.opsForValue().get(LOADED_KEY)).isEqualTo("FULL");
        assertThat(userSetCache.readIfLoaded(SET_KEY)).containsExactly("2");
    }

    @Test
    void addingAfterEmptySwitchesBackToFull() {
        // EMPTY + 新增一项 -> 创建集合，状态改回 FULL
        userSetCache.markLoaded(SET_KEY, Set.of());
        assertThat(userSetCache.readIfLoaded(SET_KEY)).isEmpty();

        userSetCache.addIfLoaded(SET_KEY, "9");

        assertThat(stringRedisTemplate.opsForValue().get(LOADED_KEY)).isEqualTo("FULL");
        assertThat(userSetCache.readIfLoaded(SET_KEY)).containsExactly("9");
    }

    @Test
    void addUpgradesLegacyFlagToFull() {
        stringRedisTemplate.opsForSet().add(SET_KEY, "1");
        stringRedisTemplate.opsForValue().set(LOADED_KEY, "1", 30, TimeUnit.MINUTES);

        userSetCache.addIfLoaded(SET_KEY, "2");

        assertThat(stringRedisTemplate.opsForValue().get(LOADED_KEY)).isEqualTo("FULL");
        assertThat(userSetCache.readIfLoaded(SET_KEY)).containsExactlyInAnyOrder("1", "2");
    }

    @Test
    void addDoesNotTrustLegacyFlagWhenSetMissing() {
        // 旧值 "1" + 集合丢失：SADD 会创建「只有新元素的集合」，历史数据就永久丢了。
        // 脚本必须拒绝维护，让下次读回源重建
        stringRedisTemplate.opsForValue().set(LOADED_KEY, "1", 30, TimeUnit.MINUTES);

        userSetCache.addIfLoaded(SET_KEY, "5");

        assertThat(stringRedisTemplate.hasKey(SET_KEY)).isFalse();
    }

    @Test
    void markLoadedOverwritesStaleLegacyFlag() {
        stringRedisTemplate.opsForSet().add(SET_KEY, "99");
        stringRedisTemplate.opsForValue().set(LOADED_KEY, "1", 30, TimeUnit.MINUTES);

        userSetCache.markLoaded(SET_KEY, Set.of("1", "2"));

        assertThat(stringRedisTemplate.opsForValue().get(LOADED_KEY)).isEqualTo("FULL");
        assertThat(userSetCache.readIfLoaded(SET_KEY)).containsExactlyInAnyOrder("1", "2");
    }

    @Test
    void markLoadedClearsStaleMembersWhenFlagExpired() {
        // 真实触发链：标记过期（30 min）但集合还在（40 min，Set TTL 更长）。
        // 这期间用户取消了点赞——维护逻辑发现未加载、正确地什么都没做，DB 里已经删了。
        // 随后回源重建：残留成员必须被清掉，否则已取消的点赞"复活"
        stringRedisTemplate.opsForSet().add(SET_KEY, "99");

        userSetCache.markLoaded(SET_KEY, Set.of("1", "2"));

        assertThat(userSetCache.readIfLoaded(SET_KEY)).containsExactlyInAnyOrder("1", "2");
    }

    // ---------- TTL 不变量 ----------

    @Test
    void setTtlIsLongerThanLoadedTtl() {
        userSetCache.markLoaded(SET_KEY, Set.of("1"));

        Long setTtl = stringRedisTemplate.getExpire(SET_KEY, TimeUnit.MINUTES);
        Long loadedTtl = stringRedisTemplate.getExpire(LOADED_KEY, TimeUnit.MINUTES);

        // 标记在时集合一定在。反过来会出现「标记在、集合已空」的窗口 = 静默显示"没点过赞"
        assertThat(setTtl).isNotNull().isGreaterThan(loadedTtl == null ? 0L : loadedTtl);
    }

    @Test
    void maintainingCollectionRefreshesBothTtls() {
        userSetCache.markLoaded(SET_KEY, Set.of("1"));
        stringRedisTemplate.expire(SET_KEY, 1, TimeUnit.MINUTES);
        stringRedisTemplate.expire(LOADED_KEY, 1, TimeUnit.MINUTES);

        userSetCache.addIfLoaded(SET_KEY, "2");

        // 不能只刷标记的 TTL：那样集合会先消失，标记还在 -> 又变成异常状态
        Long setTtl = stringRedisTemplate.getExpire(SET_KEY, TimeUnit.MINUTES);
        Long loadedTtl = stringRedisTemplate.getExpire(LOADED_KEY, TimeUnit.MINUTES);
        assertThat(setTtl).isNotNull().isGreaterThan(1L);
        assertThat(loadedTtl).isNotNull().isGreaterThan(1L);
    }
}
