package com.hailin.blogsystem.task;

import com.hailin.blogsystem.ai.rag.ArticleRagSyncService;
import com.hailin.blogsystem.ai.rag.RagRetryQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RAG 索引重试队列的消费者（第四刀 · ZSet 延迟队列）。
 *
 * **只做"捞 + 投递"，绝不在这里同步执行 RAG 同步**：
 * 三个定时任务共享同一条调度线程（`spring.task.scheduling.pool.size: 1`，
 * 见 {@link ArticleViewCountSyncTask} 的注释），而一次 RAG 同步要调 embedding + ES、
 * 可能跑几十秒。在这里同步执行会把浏览量同步和热度榜重建一起卡住。
 * 所以捞出来的任务立刻交给 {@code @Async} 线程池。
 *
 * 频率 1 分钟：队列的延迟精度就取决于它——退避最小的间隔是 60 秒，
 * 和扫描周期同量级，不需要更密。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagRetryTask {

    /** 单轮最多捞几个：一轮捞太多会让重试挤在同一时刻，反而制造尖峰 */
    private static final int BATCH_SIZE = 10;

    private final RagRetryQueue ragRetryQueue;
    private final ArticleRagSyncService articleRagSyncService;

    @Scheduled(fixedRate = 60_000)
    public void drainRetryQueue() {
        List<RagRetryQueue.Task> due = ragRetryQueue.takeDue(BATCH_SIZE);
        if (due.isEmpty()) {
            return;
        }

        log.info("RAG 重试队列捞出 {} 个到期任务", due.size());
        for (RagRetryQueue.Task task : due) {
            // 跨类调用，@Async 生效：本方法立刻返回，重试在独立线程池里跑
            articleRagSyncService.retryFromQueue(task);
        }
    }
}
