package com.hailin.blogsystem.ai.rag;

import com.hailin.blogsystem.constants.RedisConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.hailin.blogsystem.component.RedisKeyScanner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

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
    失败处理（两层）：
        第一层 · 同步快重试：同一个线程内最多 MAX_ATTEMPTS 次，间隔 1s、2s
        第二层 · 延迟队列：第一层耗尽后写失败标记，并排进 rag:retry:queue，
                 由 RagRetryTask 每分钟捞一次到期任务重试（指数退避，最多 MAX_QUEUE_RETRIES 次）
        超过第二层上限：**停止自动重试**，只留失败标记等人处理
        下次同动作成功会自动清除标记；全量重建成功会清空所有标记
        排查入口：grep 日志 "RAG .*最终失败" / "转人工处理"，或查 Redis rag:index:fail:* 与 rag:retry:queue */

public class ArticleRagSyncService {

    /** 同步快重试的最大尝试次数（含首次）——同一线程内的短间隔重试，只挡抖动 */
    private static final int MAX_ATTEMPTS = 3;
    /** 同步重试间隔：第 n 次重试前等待 n 秒 */
    private static final long RETRY_BACKOFF_SECONDS = 1L;

    /** 队列重试上限：同步重试耗尽后，再由延迟队列重试几次 */
    private static final int MAX_QUEUE_RETRIES = 5;
    /** 队列退避基数（秒）：第 n 次延迟 = 基数 × 2^(n-1) */
    private static final long QUEUE_BACKOFF_BASE_SECONDS = 60L;
    /** 退避封顶（秒）：到点就一直是这个间隔，不再翻倍 */
    private static final long QUEUE_BACKOFF_CAP_SECONDS = 3600L;

    private final ArticleRagIndexService articleRagIndexService;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedisKeyScanner redisKeyScanner;
    private final RagRetryQueue ragRetryQueue;

    @Async("articleRagTaskExecutor")
    public void indexArticle(Long articleId){

        log.info("文章 RAG 异步索引开始，articleId={}", articleId);

        try{
            int indexedChunkCount = executeWithRetry("索引", "index", articleId, 0,
                    () -> articleRagIndexService.indexArticle(articleId));
            clearFailureMark("index", articleId);
            log.info("文章 RAG 异步索引成功，articleId={}, indexedChunkCount={}", articleId, indexedChunkCount);
        }
        catch (Exception e){
            //重试已耗尽，executeWithRetry 内已写 error 日志、失败标记并排入延迟队列
        }
    }

    @Async("articleRagTaskExecutor")
    public void deleteArticleIndex(Long articleId){

        log.info("文章 RAG 异步索引删除开始，articleId={}", articleId);

        try{
            executeWithRetry("删除", "delete", articleId, 0,
                    () -> {
                        articleRagIndexService.deleteArticleIndex(articleId);
                        return 0;
                    });
            clearFailureMark("delete", articleId);
            log.info("文章 RAG 异步索引删除成功，articleId={}", articleId);
        }
        catch (Exception e) {
            //重试已耗尽，executeWithRetry 内已写 error 日志、失败标记并排入延迟队列
        }
    }

    @Async("articleRagTaskExecutor")
    public void rebuildPublishedArticles(){

        log.info("文章 RAG 全量重建开始");

        try{
            int indexedChunkCount = executeWithRetry("全量重建", "rebuild", null, 0,
                    articleRagIndexService::indexPublishedArticles);
            clearAllFailureMarks();
            log.info("文章 RAG 全量重建成功，indexedChunkCount={}", indexedChunkCount);
        }
        catch (Exception e) {
            //重试已耗尽，executeWithRetry 内已写 error 日志、失败标记并排入延迟队列
        }
    }

    /**
     * 由延迟队列触发的重试（{@link RagRetryTask} 调用）。
     *
     * **必须是 {@code @Async}**：定时任务是串行调度的（`spring.task.scheduling.pool.size: 1`），
     * 如果在这里同步执行，一次 RAG 同步（要调 embedding + ES，可能几十秒）会卡住
     * 同一条调度线程上的浏览量同步与热度榜重建。所以队列任务只负责"捞出来投递"，
     * 真正的执行丢给异步线程池——**这也是选择"ZSet + 定时捞"而不是"自己写调度循环"的原因**。
     */
    @Async("articleRagTaskExecutor")
    public void retryFromQueue(RagRetryQueue.Task task) {
        String actionKey = task.actionKey();
        Long articleId = task.articleId();

        log.info("RAG 队列重试第 {} 次开始: action={} articleId={}", task.attempt(), actionKey, articleId);

        try {
            switch (actionKey) {
                case "index" -> executeWithRetry("索引", "index", articleId, task.attempt(),
                        () -> articleRagIndexService.indexArticle(articleId));
                case "delete" -> executeWithRetry("删除", "delete", articleId, task.attempt(),
                        () -> {
                            articleRagIndexService.deleteArticleIndex(articleId);
                            return 0;
                        });
                case "rebuild" -> executeWithRetry("全量重建", "rebuild", null, task.attempt(),
                        articleRagIndexService::indexPublishedArticles);
                default -> {
                    log.warn("未知的 RAG 重试动作，跳过: {}", actionKey);
                    return;
                }
            }

            // 成功：清掉墓碑标记（重建已覆盖全量，清所有标记）
            if ("rebuild".equals(actionKey)) {
                clearAllFailureMarks();
            } else {
                clearFailureMark(actionKey, articleId);
            }
            log.info("RAG 队列重试成功: action={} articleId={}", actionKey, articleId);
        } catch (Exception e) {
            // executeWithRetry 内已写标记并排好下一次重试（或转人工）
        }
    }

    /**
     * 带重试执行同步动作：失败最多尝试 {@link #MAX_ATTEMPTS} 次，
     * 重试耗尽后写失败标记、排入延迟队列，并抛出异常。
     *
     * @param queueAttempt 这次执行本身是队列的第几次重试（0 = 业务流程首次触发）
     */
    private Integer executeWithRetry(String actionName, String actionKey, Long articleId,
                                     int queueAttempt, Supplier<Integer> task){
        for (int attempt = 1; ; attempt++) {
            try {
                return task.get();
            }
            catch (Exception e) {
                if (attempt >= MAX_ATTEMPTS) {
                    markFailure(actionKey, articleId, queueAttempt, e);
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

    private void markFailure(String actionKey, Long articleId, int queueAttempt, Exception e) {
        String message = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
        stringRedisTemplate.opsForValue().set(
                buildFailureKey(actionKey, articleId),
                message,
                java.time.Duration.ofDays(RedisConstants.RAG_INDEX_FAILURE_TTL_DAYS));

        scheduleQueueRetry(actionKey, articleId, queueAttempt);
    }

    /**
     * 排下一次延迟队列重试。
     *
     * **为什么指数退避而不是固定间隔**：外部依赖（embedding API / ES）的故障通常是**持续性**的，
     * 固定 1 分钟重试 5 次全部打在同一个故障窗口里，纯属浪费；
     * 退避让重试点自然错开——依赖早恢复了就早成功，晚恢复也不会被重试风暴淹没。
     *
     * **为什么必须有上限**：没有上限的重试会在依赖长期不可用时一直占着队列，
     * 而且**掩盖问题**——队列里始终有任务，看起来"系统在努力"，
     * 实际上永远不会成功。超过上限就停止自动重试，只留失败标记等人介入。
     */
    private void scheduleQueueRetry(String actionKey, Long articleId, int queueAttempt) {
        Long delay = nextRetryDelaySeconds(queueAttempt);
        if (delay == null) {
            log.error("RAG 自动重试已达上限（{} 次），停止重试转人工处理: {}",
                    MAX_QUEUE_RETRIES, buildFailureKey(actionKey, articleId));
            return;
        }

        ragRetryQueue.enqueue(actionKey, articleId, queueAttempt + 1, delay);
    }

    /**
     * 下一次重试该等多久。
     *
     * **抽成静态纯函数是为了能直接单测**：退避算错不会抛异常，
     * 只会让重试太密（打爆下游）或太稀（看起来像没在重试）——典型的静默错误。
     * 用一个不依赖 Spring 上下文的毫秒级单测把它钉死，比跑一遍集成测试划算得多。
     *
     * @param queueAttempt 本次是队列的第几次重试（0 = 业务流程首次失败）
     * @return 下次延迟秒数；**null = 已达上限，不再重试**
     */
    static Long nextRetryDelaySeconds(int queueAttempt) {
        // 负数归零：attempt 是从队列成员里解析出来的，脏数据不该算出负延迟
        int nextAttempt = Math.max(1, queueAttempt + 1);
        if (nextAttempt > MAX_QUEUE_RETRIES) {
            return null;
        }
        return Math.min(
                QUEUE_BACKOFF_CAP_SECONDS,
                QUEUE_BACKOFF_BASE_SECONDS * (1L << (nextAttempt - 1)));
    }

    private void clearFailureMark(String actionKey, Long articleId) {
        stringRedisTemplate.delete(buildFailureKey(actionKey, articleId));
    }

    /** 全量重建成功后清空所有失败标记（重建已覆盖旧的失败同步结果） */
    private void clearAllFailureMarks() {
        // SCAN 游标迭代 + UNLINK：原 KEYS 会阻塞 Redis 服务端单线程
        redisKeyScanner.scanAndDelete(RedisConstants.RAG_INDEX_FAILURE_KEY_PREFIX + "*");
    }

    private String buildFailureKey(String actionKey, Long articleId) {
        return RedisConstants.RAG_INDEX_FAILURE_KEY_PREFIX + actionKey + ":" + (articleId == null ? "all" : articleId);
    }

}
