package com.hailin.blogsystem;

import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.constants.SeckillKeys;
import com.hailin.blogsystem.entity.SeckillActivity;
import com.hailin.blogsystem.entity.SeckillOrder;
import com.hailin.blogsystem.mapper.SeckillActivityMapper;
import com.hailin.blogsystem.mapper.SeckillOrderMapper;
import com.hailin.blogsystem.service.SeckillService;
import com.hailin.blogsystem.utils.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 秒杀：**走真实 HTTP** 的并发验证。
 *
 * <p>与 {@link SeckillLuaTests} 的分工：那边在 JVM 内直调 Service，验的是 Lua 本身的原子性；
 * 这边用真实端口发 HTTP 请求，把 Controller、JWT 拦截器、限流拦截器、JSON 序列化
 * 全都带上——验的是「用户真的点下去会发生什么」。
 *
 * <p>它替代的是 JMeter 的常见用法：同样是并发打同一个接口，
 * 但结论由断言给出，不需要人肉看聚合报告。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SeckillHttpConcurrencyTests {

    private static final int CONCURRENT_USERS = 50;
    private static final int STOCK = 10;

    @LocalServerPort
    private int port;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private SeckillService seckillService;

    @Autowired
    private SeckillActivityMapper seckillActivityMapper;

    @Autowired
    private SeckillOrderMapper seckillOrderMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /** RestClient 是不可变且线程安全的，共用一个即可 */
    private final RestClient http = RestClient.create();

    private SeckillActivity activity;
    private final long userBase = 9_800_000_000_000L + System.nanoTime() % 1_000_000_000L;

    @BeforeEach
    void setUp() {
        activity = new SeckillActivity();
        activity.setName("HTTP 并发测试-" + System.nanoTime());
        activity.setTotalStock(STOCK);
        activity.setSoldCount(0);
        activity.setCreditAmount(1000L);
        activity.setStartAt(LocalDateTime.now().minusMinutes(5));
        activity.setEndAt(LocalDateTime.now().plusMinutes(30));
        activity.setStatus(BlogConstants.Seckill.ACTIVITY_ACTIVE);
        seckillActivityMapper.insert(activity);
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
     * 50 个不同用户同时打抢购接口，库存 10 → 恰好 10 个成功。
     *
     * <p>每个用户只发一次请求，所以不会撞限流（20 次/分钟/用户）。
     * 用 50 个不同的人而不是同一个人点 50 次，既贴近真实秒杀，
     * 也把限流这个无关变量排除在外。
     */
    @Test
    void httpConcurrentGrabNeverOversells() throws Exception {
        AtomicInteger queued = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        runConcurrently(CONCURRENT_USERS, i -> {
            String response = grabOverHttp(activity.getId(), userBase + i);
            if (response.contains("\"status\":\"QUEUED\"")) {
                queued.incrementAndGet();
            } else if (response.contains("\"status\":\"SOLD_OUT\"")) {
                soldOut.incrementAndGet();
            } else {
                unexpected.incrementAndGet();
            }
        });

        assertThat(unexpected.get())
                .as("不该出现 QUEUED / SOLD_OUT 之外的结果——429 限流、401、500 都会落到这里")
                .isZero();
        assertThat(queued.get())
                .as("恰好等于库存，多一个就是超卖")
                .isEqualTo(STOCK);
        assertThat(soldOut.get()).isEqualTo(CONCURRENT_USERS - STOCK);

        // DB 是最终裁决者，但它要等消费者跑完才 +1（fixedDelay=2000）——所以这里等收敛，不是立刻断言
        assertThat(awaitSoldCount(activity.getId(), STOCK))
                .as("DB 账本应当收敛到 %d", STOCK)
                .isEqualTo(STOCK);
        assertThat(awaitGrantedCount(activity.getId(), STOCK))
                .as("GRANTED 订单数应当收敛到 %d", STOCK)
                .isEqualTo(STOCK);
    }

    /**
     * 同一个用户再点一次 → Lua 的 SISMEMBER 当场拒绝。
     *
     * <p>关键在于这个判定**不等 DB、不等消费者**：`users` 集合是预占那一刻写进去的，
     * 而且消费完成后我们也不删它——所以哪怕第一条早就入账了，第二次点击依然被挡住。
     */
    @Test
    void repeatedGrabFromSameUserIsRejected() throws Exception {
        long userId = userBase;

        assertThat(grabOverHttp(activity.getId(), userId)).contains("\"status\":\"QUEUED\"");

        String second = grabOverHttp(activity.getId(), userId);
        assertThat(second)
                .as("一人一单由 Redis 当场判定，不需要等 DB")
                .contains("\"status\":\"DUPLICATE\"");

        assertThat(redisTemplate.opsForValue().get(SeckillKeys.stock(activity.getId())))
                .as("重复请求不该额外扣库存")
                .isEqualTo(String.valueOf(STOCK - 1));

        assertThat(awaitGrantedOrder(activity.getId(), userId))
                .as("预占最终要落成钱包到账")
                .isTrue();
    }

    // ---------- helpers ----------

    private String grabOverHttp(Long activityId, long userId) {
        return http.post()
                .uri("http://localhost:" + port + "/api/seckill/activities/" + activityId + "/grab")
                .header("Authorization", "Bearer " + jwtUtil.generateToken(userId))
                .retrieve()
                .body(String.class);
    }

    /** 等 DB 账本收敛到期望值，返回最后一次读到的数（供断言展示实际值） */
    private int awaitSoldCount(Long activityId, int expected) throws InterruptedException {
        Integer sold = null;
        for (int i = 0; i < 25; i++) {
            sold = seckillActivityMapper.selectById(activityId).getSoldCount();
            if (sold != null && sold == expected) {
                return sold;
            }
            Thread.sleep(200);
        }
        return sold == null ? -1 : sold;
    }

    private long awaitGrantedCount(Long activityId, int expected) throws InterruptedException {
        long granted = 0;
        for (int i = 0; i < 25; i++) {
            granted = seckillOrderMapper.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SeckillOrder>()
                            .eq(SeckillOrder::getActivityId, activityId)
                            .eq(SeckillOrder::getStatus, BlogConstants.Seckill.ORDER_GRANTED));
            if (granted == expected) {
                return granted;
            }
            Thread.sleep(200);
        }
        return granted;
    }

    private boolean awaitGrantedOrder(Long activityId, long userId) throws InterruptedException {
        for (int i = 0; i < 25; i++) {
            SeckillOrder order = seckillOrderMapper.selectByUserAndActivity(userId, activityId);
            if (order != null && BlogConstants.Seckill.ORDER_GRANTED.equals(order.getStatus())) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
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
            assertThat(done.await(60, TimeUnit.SECONDS)).as("并发请求未在 60 秒内跑完").isTrue();
        } finally {
            pool.shutdownNow();
        }
    }
}
