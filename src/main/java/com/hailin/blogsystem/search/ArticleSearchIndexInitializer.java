package com.hailin.blogsystem.search;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.mapper.ArticlesMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时准备搜索索引：建索引（幂等）+ 索引为空时灌一次全量数据。
 *
 * <p><b>为什么要「空时灌数据」</b>：索引文件不在 MySQL 里，重建环境 / 清空 ES 之后
 * 索引是空的，而搜索走 ES —— 结果就是**搜什么都搜不到**，且没有任何报错，
 * 看起来像是"搜索功能坏了"。首次部署必然遇到这个。
 *
 * <p><b>为什么只在「为空」时灌</b>：每次启动都全量重建，文章一多启动就慢得没法忍；
 * 而索引非空说明数据已经在里面了，后续增量由文章的生命周期钩子维护。
 *
 * <p>失败不阻断启动（与 {@code SeckillPreheatRunner} / {@code AiRunStartupRecoveryRunner} 同风格）：
 * 搜索挂了不该让整个博客起不来，降级路径会兜住。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ArticleSearchIndexInitializer implements ApplicationRunner {

    /** 首次灌数据的分批大小：一次性全量加载在文章多时会把内存顶满。 */
    private static final int REBUILD_BATCH_SIZE = 200;

    private final ArticleSearchService articleSearchService;
    private final ArticlesMapper articlesMapper;

    @Override
    public void run(ApplicationArguments args) {
        try {
            articleSearchService.ensureIndex();

            long existing = articleSearchService.count();
            // -1 = ES 不可用；>0 = 已有数据。两种情况都跳过灌数据
            if (existing != 0) {
                log.info("[SEARCH-INDEX] 索引已就绪（文档数={}），跳过首次灌数据", existing);
                return;
            }

            int total = rebuildAllPublished();
            log.info("[SEARCH-INDEX] 首次灌数据完成，共 {} 篇", total);
        } catch (Exception e) {
            log.error("[SEARCH-INDEX] 启动准备失败（搜索将走降级，其余功能不受影响）", e);
        }
    }

    /** 分批把已发布文章灌进索引。 */
    private int rebuildAllPublished() {
        int page = 1;
        int total = 0;
        while (true) {
            List<Articles> batch = articlesMapper.selectList(
                    new LambdaQueryWrapper<Articles>()
                            .eq(Articles::getStatus, BlogConstants.ArticlesStatus.PUBLISHED)
                            .orderByAsc(Articles::getId)
                            .last("LIMIT " + REBUILD_BATCH_SIZE + " OFFSET " + ((page - 1) * REBUILD_BATCH_SIZE))
            );
            if (batch.isEmpty()) {
                break;
            }
            total += articleSearchService.rebuild(batch);
            if (batch.size() < REBUILD_BATCH_SIZE) {
                break;
            }
            page++;
        }
        return total;
    }
}
