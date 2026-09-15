package com.hailin.blogsystem.task;

import com.hailin.blogsystem.service.ArticlesService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 浏览量同步 + 热度榜重建。
 *
 * **这两个任务读同一批数据，不能并发**：同步任务把 Redis 增量取走并加到 DB，
 * 重建任务读「DB 计数 + Redis 增量」算热度分。并发时重建可能读到
 * 「DB 已加、增量还没删」的中间态，导致重复计分。
 *
 * 不并发不是靠锁，是靠 `spring.task.scheduling.pool.size: 1`（串行调度）。
 * **改那个配置前先想清楚这里**——调大池子就会真的并发。
 */
@Component
@RequiredArgsConstructor
public class ArticleViewCountSyncTask {
    private final ArticlesService articlesService;

    @Scheduled(fixedRate = 30000)
    public void syncViewCount(){
        articlesService.syncViewCountToDb();
    }

    @Scheduled(fixedRate = 30 * 60 * 1000)
    public void rebuildArticleHotRank() {
        articlesService.rebuildArticleHotRank();
    }
}
