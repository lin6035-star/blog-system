package com.hailin.blogsystem.ai;

import org.springframework.ai.chat.metadata.Usage;

/**
 * token 用量累加器：把每次 LLM 调用的 usage 累加起来。
 * 工具调用（tool calling）是多轮请求，每轮都有一个 usage，需要跨轮累计才是本次请求的真实用量。
 * 流式场景下前半程 usage 是 EmptyUsage（token 字段为 null），以 getPromptTokens() != null 判断真实值。
 */
public class TokenUsageAccumulator {

    private int promptTokens;
    private int completionTokens;
    private int totalTokens;

    public void add(Usage usage) {
        if (usage == null || usage.getPromptTokens() == null) {
            return;
        }
        promptTokens += usage.getPromptTokens();
        int out = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        completionTokens += out;
        totalTokens += usage.getTotalTokens() == null ? usage.getPromptTokens() + out : usage.getTotalTokens();
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
