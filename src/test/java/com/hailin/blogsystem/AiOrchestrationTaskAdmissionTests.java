package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.task.AiOrchestrationTaskAdmissionImpl;
import com.hailin.blogsystem.ai.task.AiTaskRequest;
import com.hailin.blogsystem.ai.task.AiTaskType;
import com.hailin.blogsystem.config.AiTaskAsyncConfig;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.exception.AiTaskRejectedException;
import com.hailin.blogsystem.exception.AiTaskRejectedException.Reason;
import com.hailin.blogsystem.utils.UserContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 准入模块行为测试（设计稿 §6 清单）。
 *
 * <p>不起 Spring 上下文：准入是纯内存逻辑，用真实池配置（{@link AiTaskAsyncConfig}）
 * 构造被测实例即可，跑得快且不受外部依赖影响。
 *
 * <p>覆盖四类不变量：
 * <ol>
 *   <li><b>用户上限是硬上限</b>——并发下也不会「多跑一个」</li>
 *   <li><b>名额一定会释放</b>——任务抛异常、executor 拒绝，两条路径都不能泄漏</li>
 *   <li><b>全局容量有界</b>——池满时拒绝，不是无限排队</li>
 *   <li><b>线程复用不串用户</b>——UserContext 用完即清</li>
 * </ol>
 */
class AiOrchestrationTaskAdmissionTests {

    private final List<ThreadPoolTaskExecutor> executorsToShutdown = new ArrayList<>();

    @AfterEach
    void tearDown() {
        executorsToShutdown.forEach(ThreadPoolTaskExecutor::shutdown);
        executorsToShutdown.clear();
        UserContext.clear();
    }

    // ---------- ① 用户上限是硬上限 ----------

    @Test
    void rejectsWhenUserExceedsLimit() throws Exception {
        Fixture f = fixture(2, 4, 2);
        CountDownLatch blocker = new CountDownLatch(1);

        f.admission.submit(task(1L), blockingTask(blocker));
        f.admission.submit(task(1L), blockingTask(blocker));

        // 前两个已占满该用户名额（计数在 submit 时递增，不等 worker 启动）
        assertThatThrownBy(() -> f.admission.submit(task(1L), blockingTask(blocker)))
                .isInstanceOf(AiTaskRejectedException.class)
                .satisfies(e -> assertThat(((AiTaskRejectedException) e).getReason()).isEqualTo(Reason.USER_LIMIT));

        // precheck 同样拒绝（两个时机都要拦）
        assertThatThrownBy(() -> f.admission.precheck(1L, AiTaskType.AGENT, "ref"))
                .isInstanceOf(AiTaskRejectedException.class)
                .satisfies(e -> assertThat(((AiTaskRejectedException) e).getReason()).isEqualTo(Reason.USER_LIMIT));

        blocker.countDown();
    }

    @Test
    void anotherUserIsNotAffectedByOneUsersLimit() throws Exception {
        Fixture f = fixture(4, 4, 2);
        CountDownLatch blocker = new CountDownLatch(1);

        f.admission.submit(task(1L), blockingTask(blocker));
        f.admission.submit(task(1L), blockingTask(blocker));

        // 用户 2 不受用户 1 的名额影响
        f.admission.submit(task(2L), blockingTask(blocker));

        blocker.countDown();
    }

    /**
     * 并发提交时，任何时刻成功数都不超过上限。
     *
     * <p>这是「前置检查 + 订阅时权威判定」两段式的核心保证：竞态的结果必须是
     * 「这个任务被拒」，<b>不是</b>「多跑一个」——否则上限就从硬上限降级成了软上限。
     */
    @Test
    void concurrentSubmitNeverExceedsPerUserLimit() throws Exception {
        int threads = 16;
        int limit = 2;
        Fixture f = fixture(8, 8, limit);

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch blocker = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        ExecutorService submitters = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            submitters.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    f.admission.submit(task(1L), blockingTask(blocker));
                    accepted.incrementAndGet();
                } catch (AiTaskRejectedException ignored) {
                    // 预期：超出上限的被拒
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        submitters.shutdown();
        assertThat(submitters.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(accepted.get()).isEqualTo(limit);
        blocker.countDown();
    }

    // ---------- ② 名额一定会释放 ----------

    @Test
    void releasesSlotWhenTaskThrows() throws Exception {
        Fixture f = fixture(2, 4, 2);
        CountDownLatch executed = new CountDownLatch(1);

        f.admission.submit(task(1L), () -> {
            executed.countDown();
            throw new IllegalStateException("模拟任务失败");
        });
        assertThat(executed.await(2, TimeUnit.SECONDS)).isTrue();

        // worker 的 finally 是异步的，等名额真正回到 0
        awaitSlotReleased(f, 1L);

        // 释放后仍能提交满额度（说明计数归零，没有卡在 1）
        CountDownLatch blocker = new CountDownLatch(1);
        f.admission.submit(task(1L), blockingTask(blocker));
        f.admission.submit(task(1L), blockingTask(blocker));
        blocker.countDown();
    }

    /**
     * executor 拒绝时名额必须立即回滚。
     *
     * <p>此时 task <b>根本没进 worker</b>，{@code finally} 不会执行——回滚漏了该用户就永久少一个名额。
     * 用 {@code worker=1 / queue=0} 让池满变得可控。
     *
     * <p><b>断言方式很关键</b>（初版在这里写错过，靠反向验证才发现）：
     * 拒绝<b>之后立刻</b> precheck，此时原任务仍在跑、池仍满，所以期望的拒绝原因是
     * {@code POOL_FULL}。若回滚漏了，计数会停在 2（达到上限）而<b>先</b>撞用户检查，
     * 原因就变成 {@code USER_LIMIT}——这条断言正是靠这个区分。
     *
     * <p>初版是把 {@code maxPerUser} 设成 4、等池空后再提交，那样泄漏 1 个名额后
     * 计数停在 1 仍然 &lt; 4，测试照样绿 —— 抓不住 bug。
     */
    @Test
    void rollsBackUserSlotWhenExecutorRejects() throws Exception {
        Fixture f = fixture(1, 0, 2);
        CountDownLatch blocker = new CountDownLatch(1);

        // 占满池（worker 1 + queue 0），该用户计数 = 1
        f.admission.submit(task(999L), blockingTask(blocker));

        // 计数先涨到 2 再被 executor 拒 → 必须回滚回 1
        assertThatThrownBy(() -> f.admission.submit(task(999L), blockingTask(blocker)))
                .isInstanceOf(AiTaskRejectedException.class)
                .satisfies(e -> assertThat(((AiTaskRejectedException) e).getReason()).isEqualTo(Reason.POOL_FULL));

        assertThatThrownBy(() -> f.admission.precheck(999L, AiTaskType.AGENT, "probe"))
                .isInstanceOf(AiTaskRejectedException.class)
                .satisfies(e -> assertThat(((AiTaskRejectedException) e).getReason())
                        .as("回滚漏了的话，计数停在 2 会先撞用户检查，这里变成 USER_LIMIT")
                        .isEqualTo(Reason.POOL_FULL));

        blocker.countDown();
    }

    // ---------- ③ 全局容量有界 ----------

    @Test
    void rejectsWhenPoolAndQueueAreFull() throws Exception {
        Fixture f = fixture(2, 2, 100);   // 用户上限放到 100，让全局先满
        CountDownLatch blocker = new CountDownLatch(1);

        // 容量 = worker 2 + queue 2 = 4
        for (int i = 0; i < 4; i++) {
            f.admission.submit(task((long) (100 + i)), blockingTask(blocker));
        }

        assertThatThrownBy(() -> f.admission.submit(task(200L), blockingTask(blocker)))
                .isInstanceOf(AiTaskRejectedException.class)
                .satisfies(e -> assertThat(((AiTaskRejectedException) e).getReason()).isEqualTo(Reason.POOL_FULL));

        assertThatThrownBy(() -> f.admission.precheck(200L, AiTaskType.AGENT, "ref"))
                .isInstanceOf(AiTaskRejectedException.class)
                .satisfies(e -> assertThat(((AiTaskRejectedException) e).getReason()).isEqualTo(Reason.POOL_FULL));

        blocker.countDown();
    }

    // ---------- ④ 线程复用不串用户 ----------

    @Test
    void setsUserContextDuringTaskAndClearsItAfter() throws Exception {
        Fixture f = fixture(1, 0, 4);   // worker=1：保证探针任务跑在同一条线程上
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Long> seenInside = new AtomicReference<>();

        f.admission.submit(task(42L), () -> {
            seenInside.set(UserContext.get());
            done.countDown();
        });
        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(seenInside.get()).isEqualTo(42L);

        awaitSlotReleased(f, 42L);

        // 绕过准入模块直接往同一条 worker 线程丢探针，验证没有残留
        CountDownLatch probed = new CountDownLatch(1);
        AtomicReference<Long> leftover = new AtomicReference<>();
        f.executor.execute(() -> {
            leftover.set(UserContext.get());
            probed.countDown();
        });
        assertThat(probed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(leftover.get()).isNull();
    }

    // ---------- 测试装置 ----------

    private record Fixture(AiOrchestrationTaskAdmissionImpl admission, ThreadPoolTaskExecutor executor) {
    }

    private Fixture fixture(int workerCount, int queueCapacity, int maxPerUser) {
        BlogAiProperties props = new BlogAiProperties();
        props.getTask().setWorkerCount(workerCount);
        props.getTask().setQueueCapacity(queueCapacity);
        props.getTask().setMaxConcurrentPerUser(maxPerUser);

        // 用生产同款配置构造，连 AbortPolicy 一起测
        ThreadPoolTaskExecutor executor = new AiTaskAsyncConfig(props).aiTaskExecutor();
        executorsToShutdown.add(executor);

        return new Fixture(new AiOrchestrationTaskAdmissionImpl(executor, props, noTracer()), executor);
    }

    private static AiTaskRequest task(Long userId) {
        return AiTaskRequest.of(userId, AiTaskType.AGENT, "test:" + userId, null);
    }

    /** 会一直阻塞到 {@code blocker} 放行的任务，用来占住名额。带超时防死锁。 */
    private static Runnable blockingTask(CountDownLatch blocker) {
        return () -> {
            try {
                blocker.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    /**
     * 轮询等待该用户名额释放。
     *
     * <p>用 {@code precheck} 探测而不是读内部状态：既不碰私有字段，
     * 又正好复用生产判断路径。迟迟不释放就判失败——这正是「泄漏」要抓的现象。
     */
    private static void awaitSlotReleased(Fixture f, Long userId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            try {
                f.admission.precheck(userId, AiTaskType.AGENT, "probe");
                return;
            } catch (AiTaskRejectedException e) {
                Thread.sleep(10);
            }
        }
        throw new AssertionError("名额在 3 秒内没有释放，疑似计数泄漏：userId=" + userId);
    }

    private static ObjectProvider<Tracer> noTracer() {
        return new ObjectProvider<>() {
            @Override
            public Tracer getObject() {
                throw new UnsupportedOperationException("测试桩不提供 Tracer");
            }

            @Override
            public Tracer getIfAvailable() {
                return null;
            }
        };
    }
}
