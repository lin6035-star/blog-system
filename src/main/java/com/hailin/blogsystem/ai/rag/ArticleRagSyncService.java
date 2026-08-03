package com.hailin.blogsystem.ai.rag;

import com.hailin.blogsystem.constants.RedisConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
//新建异步RAG同步服务
  /*例如：
     用户发布文章
        MySQL保存成功
        ArticlesServiceImpl 触发 ArticleRagSyncService
        立刻返回
        后台线程慢慢调用ArticleRagIndexService
        embedding + ES 写入
    失败处理：
        单次失败自动重试（最多 MAX_ATTEMPTS 次，间隔递增）
        重试耗尽写 Redis 失败标记 rag:index:fail:{action}:{articleId}（TTL 7 天）
        下次同动作成功会自动清除标记；全量重建成功会清空所有标记
        排查入口：grep 日志 "RAG .*最终失败"，或查 Redis rag:index:fail:* */

public class ArticleRagSyncService {

    /** 同步失败最大尝试次数（含首次） */
    private static final int MAX_ATTEMPTS = 3;
    /** 重试间隔：第 n 次重试前等待 n 秒 */
    private static final long RETRY_BACKOFF_SECONDS = 1L;

    private final ArticleRagIndexService articleRagIndexService;
    private final StringRedisTemplate stringRedisTemplate;

    @Async("articleRagTaskExecutor")
    public void indexArticle(Long articleId){

        log.info("文章 RAG 异步索引开始，articleId={}", articleId);

        try{
            int indexedChunkCount = executeWithRetry("索引", "index", articleId,
                    () -> articleRagIndexService.indexArticle(articleId));
            clearFailureMark("index", articleId);
            log.info("文章 RAG 异步索引成功，articleId={}, indexedChunkCount={}", articleId, indexedChunkCount);
        }
        catch (Exception e){
            //重试已耗尽，executeWithRetry 内已写 error 日志和 Redis 失败标记
        }
    }

    @Async("articleRagTaskExecutor")
    public void deleteArticleIndex(Long articleId){

        log.info("文章 RAG 异步索引删除开始，articleId={}", articleId);

        try{
            executeWithRetry("删除", "delete", articleId,
                    () -> {
                        articleRagIndexService.deleteArticleIndex(articleId);
                        return 0;
                    });
            clearFailureMark("delete", articleId);
            log.info("文章 RAG 异步索引删除成功，articleId={}", articleId);
        }
        catch (Exception e) {
            //重试已耗尽，executeWithRetry 内已写 error 日志和 Redis 失败标记
        }
    }

    @Async("articleRagTaskExecutor")
    public void rebuildPublishedArticles(){

        log.info("文章 RAG 全量重建开始");

        try{
            int indexedChunkCount = executeWithRetry("全量重建", "rebuild", null,
                    articleRagIndexService::indexPublishedArticles);
            clearAllFailureMarks();
            log.info("文章 RAG 全量重建成功，indexedChunkCount={}", indexedChunkCount);
        }
        catch (Exception e) {
            //重试已耗尽，executeWithRetry 内已写 error 日志和 Redis 失败标记
        }
    }

    /** 带重试执行同步动作：失败最多尝试 MAX_ATTEMPTS 次，重试耗尽后写失败标记并抛出异常 */
    private Integer executeWithRetry(String actionName, String actionKey, Long articleId, Supplier<Integer> task){
        for (int attempt = 1; ; attempt++) {
            try {
                return task.get();
            }
            catch (Exception e) {
                if (attempt >= MAX_ATTEMPTS) {
                    markFailure(actionKey, articleId, e);
                    log.error("文章 RAG {}最终失败，articleId={}，已尝试 {} 次，失败标记已写入 Redis {}",
                            actionName, articleId, MAX_ATTEMPTS, buildFailureKey(actionKey, articleId), e);
                    throw e;
                }
                log.warn("文章 RAG {}失败（第 {}/{} 次尝试），articleId={}，即将重试",
                        actionName, attempt, MAX_ATTEMPTS, articleId, e);
                sleepBackoff(attempt);
            }
        }
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(RETRY_BACKOFF_SECONDS * attempt * 1000L);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("RAG 重试等待被中断", e);
        }
    }

    private void markFailure(String actionKey, Long articleId, Exception e) {
        String message = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
        stringRedisTemplate.opsForValue().set(
                buildFailureKey(actionKey, articleId),
                message,
                java.time.Duration.ofDays(RedisConstants.RAG_INDEX_FAILURE_TTL_DAYS));
    }

    private void clearFailureMark(String actionKey, Long articleId) {
        stringRedisTemplate.delete(buildFailureKey(actionKey, articleId));
    }

    /** 全量重建成功后清空所有失败标记（重建已覆盖旧的失败同步结果） */
    private void clearAllFailureMarks() {
        Set<String> keys = stringRedisTemplate.keys(RedisConstants.RAG_INDEX_FAILURE_KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    private String buildFailureKey(String actionKey, Long articleId) {
        return RedisConstants.RAG_INDEX_FAILURE_KEY_PREFIX + actionKey + ":" + (articleId == null ? "all" : articleId);
    }

}
