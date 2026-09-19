package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.LlmErrorClassifier;
import com.hailin.blogsystem.ai.LlmErrorClassifier.FailureKind;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LLM 失败分类与退避策略。
 *
 * <p>这些断言锁的是**压测暴露的那个具体缺陷**：429 被当成「不可重试」，
 * 于是限流一来就静默降级。所以核心用例是「限流必须可重试」，
 * 以及「额度类必须先于限流被识别」（两者消息里都含 429，判定顺序写反就全错）。
 */
class LlmErrorClassifierTests {

    // ---------- ① 可重试：这些失败重试有意义 ----------

    @Test
    void rateLimitIsRetryable() {
        // 压测里真实出现的那条：HTTP 429 - {"code":"limit_requests"}
        RuntimeException e = new RuntimeException("HTTP 429 - {\"code\":\"limit_requests\"}");
        assertThat(LlmErrorClassifier.classify(e)).isEqualTo(FailureKind.RATE_LIMITED);
        assertThat(LlmErrorClassifier.isRetryable(e))
                .as("这正是本刀要修的核心：限流是暂时性的，重试往往就能救回来")
                .isTrue();
    }

    @Test
    void serverErrorAndNetworkAndTimeoutAreRetryable() {
        assertThat(LlmErrorClassifier.isRetryable(new RuntimeException("HTTP 503 - upstream busy"))).isTrue();
        assertThat(LlmErrorClassifier.isRetryable(new RuntimeException("Connection reset"))).isTrue();
        assertThat(LlmErrorClassifier.isRetryable(new RuntimeException("boom", new TimeoutException()))).isTrue();
    }

    @Test
    void timeoutIsRecognizedByTypeNotByMessage() {
        // Flux.timeout 抛的 TimeoutException 消息里未必有关键词，只能靠异常链判断
        Throwable e = new RuntimeException("something odd", new IllegalStateException(new TimeoutException()));
        assertThat(LlmErrorClassifier.classify(e)).isEqualTo(FailureKind.TIMEOUT);
    }

    // ---------- ② 不可重试：重试只是白烧钱 ----------

    @Test
    void quotaAndAuthAreNotRetryable() {
        assertThat(LlmErrorClassifier.classify(new RuntimeException("401 InvalidApiKey")))
                .isEqualTo(FailureKind.QUOTA_OR_AUTH);
        assertThat(LlmErrorClassifier.isRetryable(new RuntimeException("Arrearage: 欠费"))).isFalse();
    }

    /**
     * 判定顺序的陷阱：额度类消息里**也可能带 429**（"429 quota exceeded" 这种），
     * 若限流的判断排在前面，额度问题会被误判成「可重试」，然后拿一个已经欠费的账户
     * 反复重试——白烧三次钱才放弃。
     */
    @Test
    void quotaWinsOverRateLimitWhenMessageContainsBoth() {
        assertThat(LlmErrorClassifier.classify(new RuntimeException("429 quota exceeded")))
                .as("额度判定必须排在限流之前")
                .isEqualTo(FailureKind.QUOTA_OR_AUTH);
    }

    @Test
    void contentRejectionIsNotRetryable() {
        RuntimeException e = new RuntimeException("400 data_inspection_failed");
        assertThat(LlmErrorClassifier.classify(e)).isEqualTo(FailureKind.CONTENT_REJECTED);
        assertThat(LlmErrorClassifier.isRetryable(e))
                .as("同样的内容重发多少次都会被拒")
                .isFalse();
    }

    @Test
    void unknownFailureIsNotRetryable() {
        // 保守：认不出的错误不重试，好过拿陌生错误反复打供应商
        assertThat(LlmErrorClassifier.classify(new RuntimeException("something entirely new"))).isEqualTo(FailureKind.UNKNOWN);
        assertThat(LlmErrorClassifier.isRetryable(new RuntimeException("something entirely new"))).isFalse();
    }

    @Test
    void nullThrowableIsHandled() {
        assertThat(LlmErrorClassifier.classify(null)).isEqualTo(FailureKind.UNKNOWN);
        assertThat(LlmErrorClassifier.friendlyMessage(null)).isNotBlank();
    }

    // ---------- ③ 退避：指数 + 抖动 + 封顶 ----------

    @Test
    void backoffGrowsExponentiallyAndIsCapped() {
        FailureKind kind = FailureKind.SERVER_ERROR;   // 基数 1s
        // 抖动 ±50%，所以断言的是区间而不是精确值
        assertThat(LlmErrorClassifier.backoffMillis(1, kind)).isBetween(500L, 1500L);
        assertThat(LlmErrorClassifier.backoffMillis(2, kind)).isBetween(1000L, 3000L);
        // 封顶 8s ± 50%
        assertThat(LlmErrorClassifier.backoffMillis(30, kind)).isBetween(4000L, 12000L);
    }

    /**
     * 限流的退避基数更大（2s vs 1s）。
     *
     * <p>用多次取样比均值——单次断言不可靠：抖动范围是重叠的（1s 类的上界 1500ms
     * 高于 2s 类的下界 1000ms），一次取值说明不了问题。
     */
    @Test
    void rateLimitBacksOffLongerThanServerError() {
        long serverAvg = averageBackoff(1, FailureKind.SERVER_ERROR);
        long rateAvg = averageBackoff(1, FailureKind.RATE_LIMITED);

        assertThat(rateAvg)
                .as("限流是唯一一类「重试本身会加剧问题」的失败，退避必须更长（实测均值 %d vs %d）", rateAvg, serverAvg)
                .isGreaterThan(serverAvg);
    }

    private long averageBackoff(int attempt, FailureKind kind) {
        long sum = 0;
        int rounds = 50;
        for (int i = 0; i < rounds; i++) {
            sum += LlmErrorClassifier.backoffMillis(attempt, kind);
        }
        return sum / rounds;
    }

    // ---------- ④ 文案 ----------

    @Test
    void friendlyMessageStaysReadableAndDistinct() {
        assertThat(LlmErrorClassifier.friendlyMessage(new RuntimeException("HTTP 429 rate limit")))
                .isEqualTo("AI 服务暂时繁忙，请稍后重试");
        assertThat(LlmErrorClassifier.friendlyMessage(new RuntimeException("401 InvalidApiKey")))
                .contains("额度");
        assertThat(LlmErrorClassifier.friendlyMessage(new RuntimeException("boom", new ConnectException("Connection refused"))))
                .contains("网络");
    }
}
