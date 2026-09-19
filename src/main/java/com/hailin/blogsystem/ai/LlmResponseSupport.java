package com.hailin.blogsystem.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * 非流式 LLM 响应的安全取值工具。
 *
 * 背景：项目里 8 个非流式调用点原先一律 `.call().content()`——只取文本，
 * 把 metadata 里的 usage 整个丢掉，导致 Agent 决策链 / 意图分类器 / 记忆链
 * 的 token 全部没进统计（只有流式路径统计了）。改成 `.call().chatResponse()`
 * 后每个调用点都要「安全取 usage + 安全取文本」，抽出来避免 8 份重复。
 *
 * 两个方法都做 null 防御：metadata / result / output 在异常响应、
 * 工具调用轮或空响应里都可能为 null（原先直接 .getMetadata().getUsage() 会 NPE）。
 */
@Slf4j
public final class LlmResponseSupport {

    private LlmResponseSupport() {
    }

    /**
     * 记一行非流式调用的 token 用量。
     *
     * 用于「不落库、但要看得见消耗」的副产品调用：记忆提取 / 情景记忆 / 会话摘要压缩。
     * 这些调用的 token **不进 ai_messages.token_count**——那个字段的语义是「用户直接触发的
     * 这条消息的成本」，记忆与摘要是异步副产品，混进去会让每条消息的账变得无法解释。
     * 所以只在日志里留痕，钱包计费只算主链路。
     *
     * @param purpose 用途标识（memory_extract / memory_decision / episodic_extract / summary_compress），
     *                便于按用途 grep 聚合
     */
    public static void logUsage(String purpose, ChatResponse response) {
        Usage usage = usageOf(response);
        if (usage == null) {
            return;
        }
        log.info("[PERF-AI] llm_usage purpose={} promptTokens={} completionTokens={} totalTokens={}",
                purpose, usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
    }

    /** 安全取 usage：metadata 可能为 null，返回 null 由调用方的累加器忽略。 */
    public static Usage usageOf(ChatResponse response) {
        return response == null || response.getMetadata() == null
                ? null : response.getMetadata().getUsage();
    }

    /** 安全取正文：工具调用轮 / 空响应下 result 或 output 可能为 null。 */
    public static String textOf(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }
}
