package com.hailin.blogsystem;

import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * boundedElastic 饱和时到底发生什么——「资源隔离」那一刀的实测依据。
 *
 * <p><b>为什么要实测</b>：CLAUDE.md 里「Agent / Workflow 挤在全局共享的 boundedElastic 里
 * 会互相拖慢」一直标注着<b>代码分析，尚未实测</b>。而池满了之后是「排队」「拒绝」还是
 * 「抢占」，对应三种完全不同的修法——不量清楚就动手，很可能是在修一个不存在的问题。
 *
 * <p><b>为什么要用局部小池</b>：真实的池是 {@code 10 × CPU 核数}（这台机器 20 核 → 200 线程），
 * 要造饱和得创建 200 个线程并跑满一轮，既慢又会干扰同批测试。
 * {@code newBoundedElastic(4, ...)} 模拟一台 4 核生产机——池大小只影响饱和的**绝对门槛**，
 * 不影响「满了之后发生什么」这个结论。
 */
class BoundedElasticSaturationTests {

    private static final int POOL = 4;

    /**
     * 池满 ≠ 拒绝。新任务会**排队**等位。
     *
     * <p>这就是「没有用户级并发上限」的实际后果：一个人开 5 个会话，第 5 个不是失败，
     * 而是排在前 4 个后面——他自己感觉不到，但**排在后面的其他用户会**。
     */
    @Test
    void saturatedPoolQueuesRatherThanRejects() throws Exception {
        Scheduler pool = Schedulers.newBoundedElastic(POOL, 100, "probe");
        CountDownLatch occupying = new CountDownLatch(POOL);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 0; i < POOL; i++) {
                pool.schedule(() -> {
                    occupying.countDown();
                    awaitQuietly(release);
                });
            }
            assertThat(occupying.await(5, TimeUnit.SECONDS)).as("先把全部线程占住").isTrue();

            CountDownLatch latecomer = new CountDownLatch(1);
            pool.schedule(latecomer::countDown);

            assertThat(latecomer.await(300, TimeUnit.MILLISECONDS))
                    .as("池满时后来者必须排队等位，而不是被拒绝、也不会抢占别人的线程")
                    .isFalse();

            release.countDown();
            assertThat(latecomer.await(5, TimeUnit.SECONDS))
                    .as("前一批释放后它必须能跑起来——「排队」和「拒绝」的区别就在这")
                    .isTrue();
        } finally {
            release.countDown();
            pool.dispose();
        }
    }

    /**
     * 只有**线程和队列都满**了才会拒绝。
     *
     * <p>这条回答的是「拒绝策略该放在哪一层」：Reactor 全局池的默认队列深度是 <b>100000</b>，
     * 意味着生产上几乎永远走不到这里——超载的表现是<b>延迟无限增长</b>，而不是一个明确的错误。
     * 想做到「超载时明确拒绝」，就得自己给一个有界队列，而不是指望框架兜底。
     */
    @Test
    void rejectsOnlyAfterQueueIsAlsoFull() throws Exception {
        Scheduler pool = Schedulers.newBoundedElastic(2, 2, "probe-small");
        CountDownLatch occupying = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 0; i < 2; i++) {
                pool.schedule(() -> {
                    occupying.countDown();
                    awaitQuietly(release);
                });
            }
            assertThat(occupying.await(5, TimeUnit.SECONDS)).isTrue();

            // 线程已占满，接下来提交的先进队列；队列也满了才拒绝。
            // 不写死"第几个开始抛"——queuedTaskCap 的语义是**每线程**容量
            // （2 线程 × 2 = 4 个坑），依赖这个细节会让用例跟着框架实现走
            int queued = 0;
            boolean rejected = false;
            for (int i = 0; i < 64 && !rejected; i++) {
                try {
                    pool.schedule(() -> { });
                    queued++;
                } catch (RejectedExecutionException e) {
                    rejected = true;
                }
            }

            assertThat(queued).as("满了要先排队，而不是立刻拒绝").isPositive();
            assertThat(rejected)
                    .as("线程和队列都满之后必须明确拒绝")
                    .isTrue();
        } finally {
            release.countDown();
            pool.dispose();
        }
    }

    /**
     * 确认全局池真的能同时跑 {@code max(10, CPU × 10)} 个任务——「200 线程」这个数字
     * 是所有饱和估算的基准，不能只是照着公式算出来。
     *
     * <p>验的是**下界**（能不能同时容纳这么多），不验上限——测准上限要提交几千个任务。
     * 下界够用：它决定「要多少并发才可能饱和」。
     *
     * <p>顺带兜住一个回归：如果有人给项目配了 {@code reactor.schedulers.defaultBoundedElasticSize}
     * 把它调小，这条会红。
     */
    @Test
    void globalPoolHoldsAtLeastTenTimesCpuCountTasks() throws Exception {
        int expected = Math.max(10, Runtime.getRuntime().availableProcessors() * 10);

        Set<String> threads = ConcurrentHashMap.newKeySet();
        CountDownLatch occupying = new CountDownLatch(expected);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 0; i < expected; i++) {
                Schedulers.boundedElastic().schedule(() -> {
                    threads.add(Thread.currentThread().getName());
                    occupying.countDown();
                    awaitQuietly(release);
                });
            }

            assertThat(occupying.await(15, TimeUnit.SECONDS))
                    .as("全局池应当能同时容纳 %d 个任务（Reactor 默认 max(10, CPU×10)）", expected)
                    .isTrue();
            assertThat(threads)
                    .as("这 %d 个任务必须跑在各自独立的线程上", expected)
                    .hasSize(expected);
        } finally {
            release.countDown();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
