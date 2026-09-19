package com.hailin.blogsystem.ai.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 队列重试的退避策略（第四刀）。
 *
 * **纯单测，不起 Spring 上下文**——被测对象是一个静态纯函数，
 * 毫秒级跑完。把它抽出来的原因见 {@link ArticleRagSyncService#nextRetryDelaySeconds}：
 * 退避算错是**静默错误**，不会抛异常，只会让重试太密或太稀。
 *
 * 放在 {@code ai.rag} 包下，因为方法可见性是 package-private。
 */
class ArticleRagRetryPolicyTests {

    /** 退避序列：60 → 120 → 240 → 480 → 960，每一步翻倍 */
    @Test
    void delayDoublesOnEachAttempt() {
        assertThat(ArticleRagSyncService.nextRetryDelaySeconds(0))
                .as("首次失败后等 1 分钟").isEqualTo(60L);
        assertThat(ArticleRagSyncService.nextRetryDelaySeconds(1)).isEqualTo(120L);
        assertThat(ArticleRagSyncService.nextRetryDelaySeconds(2)).isEqualTo(240L);
        assertThat(ArticleRagSyncService.nextRetryDelaySeconds(3)).isEqualTo(480L);
        assertThat(ArticleRagSyncService.nextRetryDelaySeconds(4)).isEqualTo(960L);
    }

    /** 超过上限返回 null——调用方据此停止自动重试、转人工，而不是继续排 */
    @Test
    void returnsNullOnceRetriesAreExhausted() {
        assertThat(ArticleRagSyncService.nextRetryDelaySeconds(5))
                .as("第 5 次是最后一次，第 6 次不再排")
                .isNull();
        assertThat(ArticleRagSyncService.nextRetryDelaySeconds(99))
                .as("远超上限也是 null，不会因为位移运算算出奇怪的延迟")
                .isNull();
    }

    /**
     * attempt 是从队列成员里解析出来的，理论上可能是脏数据。
     * 负数必须被夹到第一次，而不是让 `1L << -1` 变成 Long.MIN_VALUE 再溢出。
     */
    @Test
    void negativeAttemptIsClampedToFirstRetry() {
        assertThat(ArticleRagSyncService.nextRetryDelaySeconds(-1)).isEqualTo(60L);
        assertThat(ArticleRagSyncService.nextRetryDelaySeconds(-100)).isEqualTo(60L);
    }

    /** 退避必须是递增的——哪天有人把基数或位移改错，这条会比逐值断言更早报警 */
    @Test
    void delaysAreStrictlyIncreasing() {
        long previous = 0;
        for (int attempt = 0; attempt < 5; attempt++) {
            Long delay = ArticleRagSyncService.nextRetryDelaySeconds(attempt);
            assertThat(delay).isNotNull().isGreaterThan(previous);
            previous = delay;
        }
    }
}
