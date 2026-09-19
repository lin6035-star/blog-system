package com.hailin.blogsystem;

import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.constants.SeckillKeys;
import com.hailin.blogsystem.entity.SeckillActivity;
import com.hailin.blogsystem.entity.vo.SeckillResultVO;
import com.hailin.blogsystem.mapper.SeckillActivityMapper;
import com.hailin.blogsystem.mapper.SeckillOrderMapper;
import com.hailin.blogsystem.service.SeckillService;
import com.hailin.blogsystem.task.SeckillSettleTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 抢购 Lua：并发正确性。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §6.2
 *
 * <p>这个类里只有两条断言是真正的重点——**不超卖**和**一人一单**。
 * 它们锁的都是「必须一次原子完成」这件事：把查重、判断、扣减拆开执行，
 * 平时跑一万次都是对的，只在那一次并发交错的窗口里出错。
 * 而秒杀的全部意义就在于那个窗口一定会被踩到。
 *
 * <p>用真 Redis（与 {@code WalletConcurrencyTests} 同一套做法）：Lua 的原子性
 * 是 Redis 单线程模型给的，用 mock 验等于什么都没验。
 */
@SpringBootTest
class SeckillLuaTests {

    @Autowired
    private SeckillService seckillService;

    @Autowired
    private SeckillActivityMapper seckillActivityMapper;

    @Autowired
    private SeckillOrderMapper seckillOrderMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private SeckillSettleTask seckillSettleTask;

    private SeckillActivity activity;

    /** 每个用例一段独立 userId 区间，避免与别的测试类互相干扰 */
    private final long userBase = 9_600_000_000_000L + System.nanoTime() % 1_000_000_000L;

    @BeforeEach
    void setUp() {
        activity = newActivity(10);
        seckillService.preheat(activity.getId());
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(List.of(
                SeckillKeys.stock(activity.getId()),
                SeckillKeys.users(activity.getId()),
                SeckillKeys.meta(activity.getId()),
                SeckillKeys.events(activity.getId())));
    }

    /**
     * 并发抢购不超卖：库存 10，30 个人同时抢 → 恰好 10 个「排队中」。
     *
     * <p>这条锁住的是「{@code GET 判断库存} 和 {@code DECR} 必须在一个 Lua 里」。
     * 拆成两步的话，多个线程会同时读到「还剩 1」，然后一起扣成功。
     */
    @Test
    void concurrentGrabNeverOversells() throws Exception {
        AtomicInteger queued = new AtomicInteger();

        runConcurrently(30, i -> {
            if (SeckillResultVO.QUEUED.equals(
                    seckillService.grab(activity.getId(), userBase + i).getStatus())) {
                queued.incrementAndGet();
            }
        });

        assertThat(queued.get())
                .as("恰好等于库存，多一个就是超卖")
                .isEqualTo(activity.getTotalStock());
        assertThat(redisTemplate.opsForValue().get(SeckillKeys.stock(activity.getId())))
                .as("库存应当被扣到 0，而不是扣成负数")
                .isEqualTo("0");
    }

    /**
     * 一人一单：同一个人并发点 10 次，只有 1 次拿到名额。
     *
     * <p>锁的是 {@code SISMEMBER} 查重与 {@code SADD} 的原子性——
     * 「先查再写」分开做的话，10 个并发请求会全部查到「没抢过」。
     */
    @Test
    void sameUserCanOnlyGrabOnce() throws Exception {
        AtomicInteger queued = new AtomicInteger();

        runConcurrently(10, i -> {
            if (SeckillResultVO.QUEUED.equals(
                    seckillService.grab(activity.getId(), userBase).getStatus())) {
                queued.incrementAndGet();
            }
        });

        assertThat(queued.get()).isEqualTo(1);
        assertThat(redisTemplate.opsForValue().get(SeckillKeys.stock(activity.getId())))
                .as("重复请求不该额外扣库存")
                .isEqualTo(String.valueOf(activity.getTotalStock() - 1));
    }

    /**
     * 时间窗判定要「拒绝」而不是「崩掉」。
     *
     * <p>锁的是 {@code tonumber(redis.call('TIME')[1])}：TIME 返回的秒是<b>字符串</b>，
     * 少了 tonumber 会抛 {@code attempt to compare string with number}。
     * 那是<b>脚本级</b>错误——表现是所有人同时抢不到，而不是某个人抢不到，
     * 排查时脚本本身又不会在应用日志里留下任何痕迹。
     */
    @Test
    void notStartedActivityIsRejectedInsteadOfBlowingUpTheScript() {
        long future = LocalDateTime.now().plusHours(1)
                .atZone(ZoneId.systemDefault()).toEpochSecond();
        redisTemplate.opsForHash().put(SeckillKeys.meta(activity.getId()),
                "startAt", String.valueOf(future));

        SeckillResultVO result = seckillService.grab(activity.getId(), userBase);

        assertThat(result.getStatus()).isEqualTo(SeckillResultVO.NOT_ACTIVE);
        assertThat(redisTemplate.opsForValue().get(SeckillKeys.stock(activity.getId())))
                .as("被拒绝的请求不该扣库存")
                .isEqualTo(String.valueOf(activity.getTotalStock()));
    }

    /** 没预热 → 类型预检拦下，而不是去读一个不存在的库存把自己读成空 */
    @Test
    void grabWithoutPreheatIsRejected() {
        SeckillActivity cold = newActivity(5);   // 刻意不预热

        SeckillResultVO result = seckillService.grab(cold.getId(), userBase);

        assertThat(result.getStatus()).isEqualTo(SeckillResultVO.NOT_ACTIVE);
    }

    /**
     * 抢完之后事件确实进了队列。
     *
     * <p>断言写成「还在 Stream 里 <b>或者</b> 已经被入账」而不是「Stream 里恰好 1 条」：
     * 测试上下文里 {@code @Scheduled} 消费者是真会跑的，它可能在这两行之间就把事件
     * 消费并 XDEL 掉了。写死条数会得到一个偶发失败的用例，而偶发失败最终会被人忽略。
     *
     * <p>要锁的点没变——**事件必须由脚本自己 XADD**：交给 Java 侧补投的话，
     * 「扣成功但投递失败」= 名额凭空消失，用户永远停在排队中。
     */
    @Test
    void grabEnqueuesExactlyOneEvent() {
        seckillService.grab(activity.getId(), userBase);

        assertThat(redisTemplate.opsForSet().size(SeckillKeys.users(activity.getId())))
                .isEqualTo(1L);

        long inStream = redisTemplate.opsForStream().size(SeckillKeys.events(activity.getId()));
        boolean alreadySettled =
                seckillOrderMapper.selectByUserAndActivity(userBase, activity.getId()) != null;

        assertThat(inStream + (alreadySettled ? 1 : 0))
                .as("事件要么还在队列里，要么已经被消费者落成订单——恰好一次")
                .isEqualTo(1L);
    }

    // ---------- helpers ----------

    private SeckillActivity newActivity(int totalStock) {
        SeckillActivity created = new SeckillActivity();
        created.setName("测试活动-" + System.nanoTime());
        created.setTotalStock(totalStock);
        created.setSoldCount(0);
        created.setCreditAmount(1000L);
        created.setStartAt(LocalDateTime.now().minusMinutes(5));
        created.setEndAt(LocalDateTime.now().plusMinutes(30));
        created.setStatus(BlogConstants.Seckill.ACTIVITY_ACTIVE);
        seckillActivityMapper.insert(created);
        return created;
    }

    /** 让所有线程卡在同一个栅栏上再一起放行——不这么做就测不出并发交错 */
    /**
     * 预热建出来的 key 必须带 TTL。
     *
     * <p>部署模板用的是 `maxmemory-policy: volatile-lru`，而它**只淘汰设了 TTL 的 key**
     * ——没 TTL 的碰都不碰，内存满了走 OOM 而不是淘汰。这个坑项目踩过一次
     * （热度榜曾经是唯一无 TTL 的 key），秒杀这套不能重蹈，见 redis-ops-runbook.md §7.4。
     */
    @Test
    void preheatPutsTtlOnTheKeysItCreates() {
        assertThat(ttlSeconds(SeckillKeys.stock(activity.getId())))
                .as("stock 由预热创建").isGreaterThan(0);
        assertThat(ttlSeconds(SeckillKeys.meta(activity.getId())))
                .as("meta 由预热创建").isGreaterThan(0);
    }

    /**
     * {@code users} / {@code events} 是**抢购时才创建**的，预热时给它们设 TTL 是空操作
     * （key 还不存在，{@code EXPIRE} 静默返回 false）——所以必须由抢购脚本补。
     * 第一条断言锁的就是那个缺口本身，没有它这条用例会在修复前后都通过。
     */
    @Test
    void grabPutsTtlOnTheKeysItCreates() {
        assertThat(ttlSeconds(SeckillKeys.users(activity.getId())))
                .as("本用例 setup 没有任何已入账用户，users 此时还不存在")
                .isEqualTo(-2);

        seckillService.grab(activity.getId(), userBase);

        assertThat(ttlSeconds(SeckillKeys.users(activity.getId())))
                .as("users 由 SADD 创建，TTL 由抢购脚本补").isGreaterThan(0);
        assertThat(ttlSeconds(SeckillKeys.events(activity.getId())))
                .as("events 由 XADD 创建，TTL 由抢购脚本补").isGreaterThan(0);
    }

    /**
     * 消费者不能把已经删掉的 {@code events} key 凭空建回来。
     *
     * <p>这条锁的是孤儿 stream 的**根因**：{@code XGROUP CREATE ... MKSTREAM} 的语义是
     * 「stream 不存在就**创建**它」，而这个任务每 2 秒跑一轮——于是一个被清掉的活动
     * （测试删了 Redis key，但 DB 里的活动行还在、活动列表缓存也还没过期）
     * 会被它一遍遍重建。实测在本地积了 26 个永不消失的空 stream，占 db0 全部 key 的 86%。
     *
     * <p>手动调 {@code consume()} 而不是等调度器：调度周期是 2 秒，靠等会让用例变得看运气。
     */
    @Test
    void settleTaskDoesNotRecreateADeletedEventsKey() {
        seckillService.grab(activity.getId(), userBase);
        assertThat(redisTemplate.hasKey(SeckillKeys.events(activity.getId())))
                .as("抢购会通过 XADD 创建 events").isTrue();

        // 模拟「测试 @AfterEach 清完 Redis，但 DB 里的活动行还在」
        redisTemplate.delete(SeckillKeys.events(activity.getId()));

        seckillSettleTask.consume();

        assertThat(redisTemplate.hasKey(SeckillKeys.events(activity.getId())))
                .as("key 不存在 = 根本没人抢过 = 没有东西要消费，不该建它")
                .isFalse();
    }

    /**
     * 预热要覆盖**已经存在**的 {@code events} key。
     *
     * <p>它的 TTL 平时由抢购脚本补，但那个脚本只在「有人来抢」时才跑——存量 key
     * （修复上线前就建好的）和「建完之后再没人抢」的活动都等不到那一次补救。
     * 实测就是这么发现的：重启 + 预热之后 `stock`/`users`/`meta` 都拿到了 TTL，
     * 只有 `events` 还是 -1。
     */
    @Test
    void preheatBackfillsTtlOnAnExistingEventsKey() {
        seckillService.grab(activity.getId(), userBase);
        // 抹掉 TTL，模拟修复上线前建出来的存量 key
        redisTemplate.persist(SeckillKeys.events(activity.getId()));
        assertThat(ttlSeconds(SeckillKeys.events(activity.getId()))).isEqualTo(-1);

        seckillService.preheat(activity.getId());

        assertThat(ttlSeconds(SeckillKeys.events(activity.getId())))
                .as("预热后所有秒杀 key 都必须有 TTL").isGreaterThan(0);
    }

    private long ttlSeconds(String key) {
        return redisTemplate.getExpire(key, TimeUnit.SECONDS);
    }

    private void runConcurrently(int threads, IntConsumer task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        try {
            for (int i = 0; i < threads; i++) {
                int index = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        task.accept(index);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).as("并发任务未在 30 秒内跑完").isTrue();
        } finally {
            pool.shutdownNow();
        }
    }
}
