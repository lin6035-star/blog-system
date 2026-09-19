package com.hailin.blogsystem;

import com.hailin.blogsystem.entity.vo.RechargeOrderVO;
import com.hailin.blogsystem.entity.vo.WalletChangeResult;
import com.hailin.blogsystem.entity.vo.WalletPackageVO;
import com.hailin.blogsystem.service.WalletRechargeService;
import com.hailin.blogsystem.service.WalletService;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钱包并发正确性。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §3
 *
 * <p>验的是设计稿里那句「条件更新是<b>串行执行</b>的，不是先查询再判断」——
 * 如果扣减被写成「先查余额、再决定扣不扣」，并发下多个线程会凭同一份余额各扣一次。
 *
 * <p>⚠️ 测试库是 H2，行锁行为与 MySQL 未必一致。所以这里断言的是<b>结果守恒</b>
 * （成功次数 × 单次额度 == 初始余额），而不是某种具体的锁行为。
 */
@SpringBootTest
class WalletConcurrencyTests {

    @Autowired
    private WalletService walletService;

    @Autowired
    private WalletRechargeService walletRechargeService;

    private final long userId = 9_300_000_000_000L + System.nanoTime() % 1_000_000_000L;

    /**
     * 20 个线程抢着扣 100，余额只有 1000 —— 必须恰好成功 10 次。
     *
     * <p>出错的两个方向都覆盖到了：门槛失效会成功 20 次（余额 -1000），
     * 而写成「先查再扣」则可能成功超过 10 次。
     */
    @Test
    void concurrentDeductionNeverOverspends() throws Exception {
        walletService.increase(userId, 1000, "RECHARGE", "RECHARGE_ORDER",
                "ORDER-CONC", "RECHARGE:ORDER-CONC", null);

        int threads = 20;
        long perDeduction = 100;
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        runConcurrently(threads, idx -> {
            try {
                WalletChangeResult result = walletService.decrease(userId, perDeduction,
                        "AI_RESERVE", "AI_BILLING", "B-CONC-" + idx, "AI_RESERVE:B-CONC-" + idx, null);
                if (result.success()) {
                    success.incrementAndGet();
                } else {
                    rejected.incrementAndGet();
                }
            } catch (Throwable t) {
                errors.add(t);
            }
        });

        assertThat(errors).as("并发扣减不应抛出异常：%s", errors).isEmpty();
        assertThat(success.get()).isEqualTo(10);
        assertThat(rejected.get()).isEqualTo(10);
        assertThat(walletService.getWallet(userId).getBalance()).isZero();
    }

    /**
     * 8 个线程同时支付同一张订单 —— 只能加一次钱。
     *
     * <p>并发的落点有两处：订单状态 CAS 只让一个事务推进状态；
     * 记账幂等键（RECHARGE:{orderNo}）是最后一层，即使前面漏了也不会双倍加钱。
     */
    @Test
    void concurrentPayCreditsOnlyOnce() throws Exception {
        UserContext.set(userId);
        WalletPackageVO pkg = walletRechargeService.listPackages().get(0);
        RechargeOrderVO order = walletRechargeService.createOrder(pkg.getCode());
        UserContext.clear();

        int threads = 8;
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        runConcurrently(threads, idx -> {
            // UserContext 是 ThreadLocal，工作线程里必须各自设置
            UserContext.set(userId);
            try {
                walletRechargeService.pay(order.getOrderNo());
            } catch (Throwable t) {
                // 并发下 CAS 失败的线程抛 CONFLICT 是预期内的
                errors.add(t);
            } finally {
                UserContext.clear();
            }
        });

        // 无论多少线程参与，余额只能加一次
        assertThat(walletService.getWallet(userId).getBalance()).isEqualTo(pkg.getCreditAmount());
        // 失败的那些必须是「订单状态已变更」这类并发冲突，而不是别的错误
        assertThat(errors).allSatisfy(t ->
                assertThat(t.getMessage()).contains("订单状态已变更"));
    }

    // ---------- helpers ----------

    private interface IndexedTask {
        void run(int index) throws Exception;
    }

    private void runConcurrently(int threads, IndexedTask task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        task.run(idx);
                    } catch (Exception e) {
                        // 交由任务自己收集；这里只保证 latch 一定归零
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).as("并发任务应在超时前完成").isTrue();
        } finally {
            pool.shutdownNow();
        }
    }
}
