package com.hailin.blogsystem.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.Usage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * token 用量累加 + 流式峰值跟踪。纯单测，不起 Spring 上下文。
 *
 * **回归背景**（实测于 2026-09-17）：聊天与 Workflow 的 token **全是真实值的 2 倍**。
 * 日志证据是两个带 usage 的 chunk 出现在流末尾、值完全相同：
 *
 * <pre>
 * chunk prompt=5498 completion=767 total=6265 acc=0      ← 第 1 个
 * chunk prompt=5498 completion=767 total=6265 acc=6265   ← 第 2 个，同样的值又加了一遍
 * stopEvent accTotal=12530                                ← 6265 × 2
 * </pre>
 *
 * 根因：OpenAI 规范只在流末尾补**一个**带 usage 的收尾 chunk，但兼容实现（实测 Qwen）
 * **在最后一个正常 chunk 里也带一份同样的累计值**——而代码对每个 chunk 都调 {@code usage.add}。
 *
 * 修法：单次流式调用只跟踪**峰值**，结束后提交一次（累计值单调不减，峰值即最终值）。
 */
class TokenUsageAccumulatorTests {

    /** 核心回归：同一个累计值出现两次，结果必须是 6265 而不是 12530 */
    @Test
    void repeatedCumulativeUsageIsCountedOnce() {
        TokenUsageAccumulator accumulator = new TokenUsageAccumulator();
        AtomicReference<Usage> peak = new AtomicReference<>();

        TokenUsageAccumulator.trackPeak(peak, usage(0, 0, 0));           // 流前半程的空 usage
        TokenUsageAccumulator.trackPeak(peak, usage(5498, 767, 6265));   // 第 1 个真 usage
        TokenUsageAccumulator.trackPeak(peak, usage(5498, 767, 6265));   // 兼容实现多给的那个
        accumulator.add(peak.get());

        assertThat(accumulator.getTotalTokens())
                .as("同一个累计值出现两次只能算一次——修复前这里会得到 12530")
                .isEqualTo(6265);
        assertThat(accumulator.getPromptTokens()).isEqualTo(5498);
        assertThat(accumulator.getCompletionTokens()).isEqualTo(767);
    }

    /**
     * **对照组：修复前的写法会翻倍。**
     *
     * 这个用例不测生产代码，它把 bug 本身钉在这里——如果有人日后"简化"回逐 chunk 累加，
     * 只看上面那条峰值用例通过就以为没事，这条会提醒他代价是什么。
     */
    @Test
    void perChunkAccumulationWouldDoubleTheResult() {
        TokenUsageAccumulator oldWay = new TokenUsageAccumulator();

        oldWay.add(usage(0, 0, 0));
        oldWay.add(usage(5498, 767, 6265));
        oldWay.add(usage(5498, 767, 6265));

        assertThat(oldWay.getTotalTokens())
                .as("这就是修复前的行为：真实值 6265 被记成 12530")
                .isEqualTo(12530);
    }

    /** 累计值单调递增时取最大的那个（工具调用多轮：后一轮的累计包含前一轮） */
    @Test
    void peakKeepsTheLargestCumulativeValue() {
        AtomicReference<Usage> peak = new AtomicReference<>();

        TokenUsageAccumulator.trackPeak(peak, usage(1000, 100, 1100));
        TokenUsageAccumulator.trackPeak(peak, usage(3000, 500, 3500));

        assertThat(peak.get().getTotalTokens()).isEqualTo(3500);
    }

    /** null 或不带 total 的 usage 不能污染峰值——带 usage 的 chunk 后面还跟着大量空 chunk */
    @Test
    void nullUsageDoesNotClobberPeak() {
        AtomicReference<Usage> peak = new AtomicReference<>();

        TokenUsageAccumulator.trackPeak(peak, usage(5498, 767, 6265));
        TokenUsageAccumulator.trackPeak(peak, null);
        TokenUsageAccumulator.trackPeak(peak, usage(0, 0, null));

        assertThat(peak.get().getTotalTokens())
                .as("空 chunk 排在真 usage 之后，不能把峰值冲掉")
                .isEqualTo(6265);
    }

    /** 峰值只约束"单次流式调用内部"；跨调用仍然是累加（Workflow 每个 step 一次调用） */
    @Test
    void separateCallsStillAccumulate() {
        TokenUsageAccumulator accumulator = new TokenUsageAccumulator();

        accumulator.add(usage(1000, 100, 1100));
        accumulator.add(usage(2000, 200, 2200));

        assertThat(accumulator.getTotalTokens()).isEqualTo(3300);
    }

    /** 空 usage 被 add 的护栏拦下，不参与累加 */
    @Test
    void emptyUsageIsIgnoredByAdd() {
        TokenUsageAccumulator accumulator = new TokenUsageAccumulator();

        accumulator.add(usage(null, null, null));

        assertThat(accumulator.getTotalTokens()).isZero();
    }

    /**
     * **复现线上故障**：`doFinally` 的回调在 `onComplete` **传播到下游之后**才执行，
     * 所以 `Flux.concat(前置事件, dataStream, stopEvent)` 里，负责落库的 `stopEvent`
     * 被订阅时 usage 还没提交——**落库读到的永远是 0**。
     *
     * ⚠️ **必须用异步流才能复现**：同步的 `Flux.just(...)` 会走另一条内部路径，
     * `doFinally` 恰好赶在下游之前，测试会假绿。第一版修复就是栽在这里——
     * 单测全绿、线上全 0，日志里 `stopEvent acc=0` 排在 `doFinally accAfter=6480` 前面。
     */
    @Test
    void doFinallyAloneIsTooLateForTheDownstreamCallback() {
        TokenUsageAccumulator usage = new TokenUsageAccumulator();
        AtomicReference<Usage> peak = new AtomicReference<>();

        Flux<String> dataStream = Flux.just("chunk-1", "chunk-2")
                .delayElements(Duration.ofMillis(1))
                .doOnNext(chunk -> TokenUsageAccumulator.trackPeak(peak, usage(5498, 767, 6265)))
                .doFinally(signalType -> usage.add(peak.get()));

        Mono<String> stopEvent = Mono.fromSupplier(() -> "acc=" + usage.getTotalTokens());

        List<String> result = Flux.concat(Flux.just("param-event"), dataStream, stopEvent)
                .collectList()
                .block();

        assertThat(result).isNotNull();
        assertThat(result.get(result.size() - 1))
                .as("doFinally 提交得太晚，下游读到 0——这就是线上现象")
                .isEqualTo("acc=0");
    }

    /**
     * **修复方案**：`doOnComplete` 的回调在 `onComplete` 传播给下游**之前**执行，
     * 所以下游读得到；再用 `doFinally` 兜底 cancel（客户端断开时 token 已消耗也应计上），
     * 两者靠 `AtomicBoolean` 保证只提交一次。
     */
    @Test
    void doOnCompleteCommitsInTimeForTheDownstreamCallback() {
        TokenUsageAccumulator usage = new TokenUsageAccumulator();
        AtomicReference<Usage> peak = new AtomicReference<>();
        AtomicBoolean committed = new AtomicBoolean(false);
        Runnable commit = () -> {
            if (committed.compareAndSet(false, true)) {
                usage.add(peak.get());
            }
        };

        Flux<String> dataStream = Flux.just("chunk-1", "chunk-2")
                .delayElements(Duration.ofMillis(1))
                .doOnNext(chunk -> TokenUsageAccumulator.trackPeak(peak, usage(5498, 767, 6265)))
                .doOnComplete(commit)
                .doFinally(signalType -> commit.run());

        Mono<String> stopEvent = Mono.fromSupplier(() -> "acc=" + usage.getTotalTokens());

        List<String> result = Flux.concat(Flux.just("param-event"), dataStream, stopEvent)
                .collectList()
                .block();

        assertThat(result).isNotNull();
        assertThat(result.get(result.size() - 1))
                .as("doOnComplete 赶在下游订阅之前提交，读到的才是真值 6265")
                .isEqualTo("acc=6265");
    }

    private static Usage usage(Integer prompt, Integer completion, Integer total) {
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(prompt);
        when(usage.getCompletionTokens()).thenReturn(completion);
        when(usage.getTotalTokens()).thenReturn(total);
        return usage;
    }
}
