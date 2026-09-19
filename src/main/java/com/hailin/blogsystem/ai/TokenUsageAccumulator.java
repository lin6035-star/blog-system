package com.hailin.blogsystem.ai;

import org.springframework.ai.chat.metadata.Usage;

import java.util.concurrent.atomic.AtomicReference;

/**
 * token 用量累加器：把每次 LLM 调用的 usage 累加起来。
 * 工具调用（tool calling）是多轮请求，每轮都有一个 usage，需要跨轮累计才是本次请求的真实用量。
 *
 * **流式场景必须配合 {@link #trackPeak} 使用，不能对每个 chunk 直接调用 {@link #add}。**
 * 原因见 {@link #trackPeak} 的注释——直接逐 chunk 累加会让结果成倍放大（实测 ×2）。
 */
public class TokenUsageAccumulator {

    private int promptTokens;
    private int completionTokens;
    private int totalTokens;

    /**
     * 跟踪单次流式调用的**峰值 usage**，流结束后用 {@link #add} 提交一次。
     *
     * **为什么不能对每个 chunk 直接 add**：同一个"累计 usage"会出现在**多个 chunk** 里。
     * OpenAI 规范是在流末尾补**一个** `choices:[]` 的收尾 chunk 带 usage，但兼容实现
     * （实测 Qwen）在最后一个正常 chunk 里**也带一份同样的累计值**——逐 chunk 累加就翻倍。
     *
     * **为什么取峰值等价于取最终值**：Spring AI 的 `UsageCalculator.getCumulativeUsage`
     * 返回的是"到目前为止的累计"（要么是上一轮的累计、要么是累计+本轮），**单调不减**，
     * 所以最大值就是最终累计。
     *
     * **为什么不取"最后一个 chunk 的 usage"**：带 usage 的 chunk 后面还跟着大量纯文本
     * chunk（usage 为空），取最后一个会取到空值。峰值法同时也**抗流中断**
     * （客户端断开时已经消耗的 token 仍然算得出来）。
     *
     * @param peak     单次流式调用的峰值容器，调用方持有
     * @param candidate 本 chunk 的 usage，允许为 null
     */
    public static void trackPeak(AtomicReference<Usage> peak, Usage candidate) {
        if (candidate == null || candidate.getTotalTokens() == null) {
            return;
        }
        Usage current = peak.get();
        if (current == null || current.getTotalTokens() == null
                || candidate.getTotalTokens() > current.getTotalTokens()) {
            peak.set(candidate);
        }
    }

    /**
     * 空安全累加：容器为 null 时静默忽略。
     * 用于「调用方可能不需要统计」的场景——非流式调用点的 usage 是出参，
     * 实现方不该为了统计去写 null 判断（也不该因为调用方传 null 就 NPE）。
     */
    public static void addTo(TokenUsageAccumulator accumulator, Usage usage) {
        if (accumulator != null) {
            accumulator.add(usage);
        }
    }

    public void add(Usage usage) {
        if (usage == null || usage.getPromptTokens() == null) {
            return;
        }
        promptTokens += usage.getPromptTokens();
        int out = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        completionTokens += out;
        totalTokens += usage.getTotalTokens() == null ? usage.getPromptTokens() + out : usage.getTotalTokens();
    }

    /** 合并另一个累加器的用量（如把一个暂存容器并进另一个）。null 安全。 */
    public void add(TokenUsageAccumulator other) {
        if (other == null) {
            return;
        }
        promptTokens += other.getPromptTokens();
        completionTokens += other.getCompletionTokens();
        totalTokens += other.getTotalTokens();
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }
}
