package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.rag.RagRetryQueue;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.task.RagRetryTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 重试延迟队列（第四刀 · ZSet 延迟队列）。
 *
 * 重点覆盖两件事：
 * 1. **延迟语义**：没到期取不出来，到期才取得到——这是 ZSet 当延迟队列的本体
 * 2. **取出即删除**：第二次 takeDue 不能拿到同一个任务。
 *    这是把 ZRANGEBYSCORE + ZREM 打包成 Lua 的全部意义所在——
 *    分开写时"上一轮还没删完、下一轮已经读到"会让同一个任务被重试两次
 */
@SpringBootTest
class RagRetryQueueTests {

    @Autowired
    private RagRetryQueue ragRetryQueue;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * **必须挡掉队列消费者**：`@Scheduled` 在 `@SpringBootTest` 里照样启动，
     * 而且 `fixedRate` 的首次执行是**立即**的——它会抢在测试断言之前
     * 把到期任务全部捞走，让队列看起来"莫名其妙空了"。
     *
     * 这正是本测试类第一次跑时的真实故障：`takeDue` 返回空、队列 size 归零，
     * 一度被误判成"ZREM 删多了"。项目里 `ArticleDetailMutexTests` 挡
     * `ArticleViewCountSyncTask` 也是同样的理由。
     */
    @MockBean
    private RagRetryTask ragRetryTask;

    @BeforeEach
    @AfterEach
    void cleanQueue() {
        stringRedisTemplate.delete(RedisConstants.RAG_RETRY_QUEUE_KEY);
    }

    @Test
    void enqueueThenTakeDue() {
        ragRetryQueue.enqueue("index", 123L, 1, 0);

        List<RagRetryQueue.Task> due = ragRetryQueue.takeDue(10);

        assertThat(due).hasSize(1);
        assertThat(due.get(0).actionKey()).isEqualTo("index");
        assertThat(due.get(0).articleId()).isEqualTo(123L);
        assertThat(due.get(0).attempt()).isEqualTo(1);
    }

    /** 没到期的取不出来，而且它**还在队列里**等着——不是被丢掉了 */
    @Test
    void notYetDueIsNotTaken() {
        ragRetryQueue.enqueue("index", 123L, 1, 60);

        assertThat(ragRetryQueue.takeDue(10)).isEmpty();
        assertThat(ragRetryQueue.size())
                .as("没到期也仍然在队列里，等下一轮")
                .isEqualTo(1);
    }

    /** 取走即删除——第二次不能再拿到同一个任务 */
    @Test
    void takenTaskIsRemovedAtomically() {
        ragRetryQueue.enqueue("index", 123L, 1, 0);

        assertThat(ragRetryQueue.takeDue(10)).hasSize(1);
        assertThat(ragRetryQueue.takeDue(10))
                .as("第二次不该再拿到同一个任务，否则会被重试两次")
                .isEmpty();
        assertThat(ragRetryQueue.size()).isZero();
    }

    /** 队列 key 必须带 TTL——它同样是"允许丢失"的数据，不能破坏 volatile-lru 的淘汰前提 */
    @Test
    void queueKeyCarriesTtl() {
        ragRetryQueue.enqueue("index", 123L, 1, 60);

        Long ttl = stringRedisTemplate.getExpire(RedisConstants.RAG_RETRY_QUEUE_KEY, TimeUnit.SECONDS);
        assertThat(ttl).isNotNull().isGreaterThan(0);
    }

    /** 全量重建的 articleId 是 null，往返后仍是 null（member 里用 all 占位） */
    @Test
    void nullArticleIdRoundTrips() {
        ragRetryQueue.enqueue("rebuild", null, 2, 0);

        List<RagRetryQueue.Task> due = ragRetryQueue.takeDue(10);

        assertThat(due).hasSize(1);
        assertThat(due.get(0).actionKey()).isEqualTo("rebuild");
        assertThat(due.get(0).articleId()).isNull();
        assertThat(due.get(0).attempt()).isEqualTo(2);
    }

    /** 单轮上限生效：一次取太多会把重试挤在同一时刻 */
    @Test
    void takeDueRespectsLimit() {
        for (int i = 1; i <= 5; i++) {
            ragRetryQueue.enqueue("index", (long) i, 1, 0);
        }

        assertThat(ragRetryQueue.size()).as("诊断：入队后队列里应该有 5 个").isEqualTo(5);

        List<RagRetryQueue.Task> taken = ragRetryQueue.takeDue(2);
        assertThat(taken).as("诊断：单轮上限 2 有没有生效").hasSize(2);
        assertThat(ragRetryQueue.size())
                .as("剩下的留在队列里等下一轮")
                .isEqualTo(3);
    }
}
