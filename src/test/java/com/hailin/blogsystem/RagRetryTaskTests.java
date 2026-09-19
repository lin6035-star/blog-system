package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.rag.ArticleRagSyncService;
import com.hailin.blogsystem.ai.rag.RagRetryQueue;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.task.RagRetryTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 定时任务把到期任务投递给异步执行（第四刀）。
 *
 * **测的是"投递"这一环**：队列本身（延迟语义、原子取出）由
 * {@link RagRetryQueueTests} 覆盖；这里只回答"捞出来之后有没有真的交出去"——
 * 这一环断了，队列就只是堆着墓碑，和改造前没有区别。
 *
 * **为什么用 mock 掉的 Bean + 手动 new 的实例**：
 * 1. `@MockBean RagRetryTask` 挡掉 `@Scheduled`——它首次执行是立即的，
 *    会在测试准备阶段就把任务捞走（本项目已踩过这个坑）
 * 2. 手动 `new` 一个真实实例来调 `drainRetryQueue()`，逻辑才是真的，
 *    且调用时机完全由测试决定
 */
@SpringBootTest
class RagRetryTaskTests {

    @Autowired
    private RagRetryQueue ragRetryQueue;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private RagRetryTask ragRetryTask;

    @MockBean
    private ArticleRagSyncService articleRagSyncService;

    /** 真实逻辑，但由测试手动驱动 */
    private RagRetryTask drivenTask;

    @BeforeEach
    void setUp() {
        drivenTask = new RagRetryTask(ragRetryQueue, articleRagSyncService);
        cleanQueue();
    }

    @AfterEach
    void tearDown() {
        cleanQueue();
    }

    @Test
    void drainsDueTasksToAsyncService() {
        ragRetryQueue.enqueue("index", 11L, 1, 0);
        ragRetryQueue.enqueue("index", 22L, 2, 0);

        drivenTask.drainRetryQueue();

        ArgumentCaptor<RagRetryQueue.Task> captor = ArgumentCaptor.forClass(RagRetryQueue.Task.class);
        verify(articleRagSyncService, times(2)).retryFromQueue(captor.capture());

        assertThat(captor.getAllValues())
                .extracting(RagRetryQueue.Task::articleId)
                .containsExactlyInAnyOrder(11L, 22L);
        assertThat(ragRetryQueue.size())
                .as("投递后队列应清空——取走即删，这也是拿到 Lua 里做的原因")
                .isZero();
    }

    /** 没到期的任务不该被提前投递出去，否则退避就形同虚设 */
    @Test
    void doesNotDrainNotYetDueTasks() {
        ragRetryQueue.enqueue("index", 11L, 1, 60);

        drivenTask.drainRetryQueue();

        verify(articleRagSyncService, never()).retryFromQueue(any());
        assertThat(ragRetryQueue.size()).isEqualTo(1);
    }

    /** 队列为空时安静返回，不做无谓的投递也不报错 */
    @Test
    void emptyQueueIsNoop() {
        drivenTask.drainRetryQueue();

        verify(articleRagSyncService, never()).retryFromQueue(any());
    }

    private void cleanQueue() {
        stringRedisTemplate.delete(RedisConstants.RAG_RETRY_QUEUE_KEY);
    }
}
