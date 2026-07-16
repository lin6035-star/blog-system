package com.hailin.blogsystem.task;

import com.hailin.blogsystem.service.ArticlesService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ArticleViewCountSyncTask {
    private final ArticlesService articlesService;

    @Scheduled(fixedRate = 30000)
    public void syncViewCount(){
        articlesService.syncViewCountToDb();
    }
}
