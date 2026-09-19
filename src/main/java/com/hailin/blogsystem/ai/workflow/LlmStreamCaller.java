package com.hailin.blogsystem.ai.workflow;

import com.hailin.blogsystem.ai.LlmErrorClassifier;
import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.entity.dto.AiWorkflowStep;
import com.hailin.blogsystem.utils.MdcContext;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LLM 流式调用骨架：streamUsage + timeout + chatResponse + delta 提取过滤
 * + usage 累计 + 空内容检查 + 异常分类。
 * prompt 文本、maxTokens、emit 的 step/field 由调用点传入；
 * 后处理（cleanMarkdown / JSON 解析 / usage 落库）由调用点基于返回值做。
 */
@Slf4j
@Component
public class LlmStreamCaller {

    //流式调用结果：content 原始文本 + usage（含工具调用多轮累计）
    public record LlmStreamResult(String content, TokenUsageAccumulator usage) {
    }

    /** 流式重试上限：首次 + 2 次重试。用户在等进度，不适合拖太久。 */
    private static final int STREAM_MAX_ATTEMPTS = 3;

    private final ChatClient.Builder chatClientBuilder;
    private final ObjectProvider<Tracer> tracerProvider;

    @Autowired
    public LlmStreamCaller(
            ChatClient.Builder chatClientBuilder,
            ObjectProvider<Tracer> tracerProvider
    ) {
        this.chatClientBuilder = chatClientBuilder;
        this.tracerProvider = tracerProvider;
    }

    // 测试和不启用 tracing 的轻量调用入口。
    public LlmStreamCaller(ChatClient.Builder chatClientBuilder) {
        this(chatClientBuilder, null);
    }

    public LlmStreamResult call(
            String stepPrefix,        //异常消息前缀："大纲生成失败："
            AiWorkflowStep step,      //emitContent 的 step
            String field,             //emitContent 的 field："outline"/"plan"...
            AiWorkflowStepEmitter emitter,
            String systemPrompt,
            String userPrompt,
            Integer maxTokens         //可空
    ) {
        return call(stepPrefix, step, field, emitter, systemPrompt, userPrompt, maxTokens, false);
    }

    /**
     * @param jsonMode 要求服务端按 JSON 对象约束输出（response_format=json_object）。
     *                  实测（2026-09-13，qwen3.6-plus-2026-04-02）：长 prompt + 长 JSON 输出场景下
     *                  （学习计划「调整模式」：prompt 2749 字符、输出 2200-2700 字符）
     *                  **约 57% 概率漏掉最外层收尾 `]}`**，而 finish_reason 仍是 stop——
     *                  流完整结束、token 远未触顶，纯粹是模型自己没写完；
     *                  开启本参数后同场景 7/7 全部返回合法 JSON。
     *                  **只对「输出纯 JSON」的调用点开启**：Markdown 输出的步骤（大纲/草稿/重写）开了会坏。
     */
    public LlmStreamResult call(
            String stepPrefix,
            AiWorkflowStep step,
            String field,
            AiWorkflowStepEmitter emitter,
            String systemPrompt,
            String userPrompt,
            Integer maxTokens,
            boolean jsonMode
    ) {
        // 已输出字符数：跨重试持有，用来判定「还能不能重试」（见 shouldRetryStream）
        AtomicInteger emittedChars = new AtomicInteger();

        for (int attempt = 1; ; attempt++) {
            try {
                return callOnce(stepPrefix, step, field, emitter,
                        systemPrompt, userPrompt, maxTokens, jsonMode, emittedChars);
            } catch (RuntimeException e) {
                if (!shouldRetryStream(step, attempt, emittedChars, e)) {
                    throw e;
                }
                sleepBeforeRetry(step, attempt, e);
            }
        }
    }

    /**
     * 流式调用能不能重试。
     *
     * <p><b>唯一的硬约束：已经吐出过内容就不再重试</b>——重试会把前面的内容重发一遍，
     * 用户看到重复输出。首字节之前失败是「用户什么都还没看到」，重试无痕；
     * 首字节之后失败就只能到此为止（调用方记 FAILED，用户手动 retry）。
     *
     * <p><b>为什么这里要自己实现</b>：Spring AI 自带的 RetryTemplate 只作用于 {@code .call()}，
     * {@code stream()} 路径完全没有重试（字节码里 retryTemplate 只在 internalCall 中被引用）。
     * 而本项目 Workflow 的生成步骤（大纲 / 草稿 / 重写 / 计划 / 拆解）**全是流式**——
     * 恰恰是最需要重试的地方。
     *
     * <p>为什么重试次数不配置化：这个值是「用户愿意等多久」的体现而不是环境差异，
     * 3 次（首次 + 2 次重试）配合下面的退避最长约 6 秒，是「等待」和「放弃」之间的折中。
     */
    private boolean shouldRetryStream(AiWorkflowStep step, int attempt,
                                      AtomicInteger emittedChars, RuntimeException e) {
        if (emittedChars.get() > 0) {
            log.warn("[LLM-RETRY] step={} 不重试：已输出 {} 字符，重试会产生重复内容",
                    step, emittedChars.get());
            return false;
        }
        if (attempt >= STREAM_MAX_ATTEMPTS) {
            return false;
        }
        if (!LlmErrorClassifier.isRetryable(e)) {
            log.warn("[LLM-RETRY] step={} 不重试：失败类型 {} 重试无意义",
                    step, LlmErrorClassifier.classify(e));
            return false;
        }
        return true;
    }

    private void sleepBeforeRetry(AiWorkflowStep step, int attempt, RuntimeException e) {
        LlmErrorClassifier.FailureKind kind = LlmErrorClassifier.classify(e);
        long delayMs = LlmErrorClassifier.backoffMillis(attempt, kind);
        log.warn("[LLM-RETRY] step={} 第 {} 次失败（{}），退避 {}ms 后重试 error={}",
                step, attempt, kind, delayMs, e.getMessage());
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    /** 单次流式调用（不含重试）。原 call 的方法体原样搬入，唯一改动是累加 emittedChars。 */
    private LlmStreamResult callOnce(
            String stepPrefix,
            AiWorkflowStep step,
            String field,
            AiWorkflowStepEmitter emitter,
            String systemPrompt,
            String userPrompt,
            Integer maxTokens,
            boolean jsonMode,
            AtomicInteger emittedChars
    ) {
        AiWorkflowStepEmitter safeEmitter = emitter == null ? AiWorkflowStepEmitter.noop() : emitter;

        long llmStart = System.currentTimeMillis();
        Tracer tracer = tracerProvider == null ? null : tracerProvider.getIfAvailable();
        MdcContext.LogContext logContext =
                MdcContext.captureWithTrace(tracer, MdcContext.capture());

        AtomicBoolean firstChunkLogged =
                new AtomicBoolean(false);

        AtomicLong firstChunkDelayMs =
                new AtomicLong(-1L);
        AtomicInteger responseCount = new AtomicInteger();
        AtomicInteger resultCount = new AtomicInteger();
        AtomicInteger emptyTextResponseCount = new AtomicInteger();
        AtomicInteger toolCallResponseCount = new AtomicInteger();
        AtomicReference<String> responseModel = new AtomicReference<>();
        Set<String> finishReasons = ConcurrentHashMap.newKeySet();
        Set<String> generationMetadataKeys = ConcurrentHashMap.newKeySet();

        try {
            StringBuilder content = new StringBuilder();
            TokenUsageAccumulator usage = new TokenUsageAccumulator();
            // 单次流式调用只记峰值，流结束后提交一次（原因见 TokenUsageAccumulator.trackPeak）
            AtomicReference<Usage> peakUsage = new AtomicReference<>();

            int systemPromptChars =
                    systemPrompt == null ? 0 : systemPrompt.length();

            int userPromptChars =
                    userPrompt == null ? 0 : userPrompt.length();

            log.info(
                    "[PERF-WORKFLOW] llm_start step={} systemPromptChars={} userPromptChars={} maxTokens={} jsonMode={}",
                    step,
                    systemPromptChars,
                    userPromptChars,
                    maxTokens,
                    jsonMode
            );

            OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().streamUsage(true);
            if (maxTokens != null) {
                options.maxTokens(maxTokens);
            }
            if (jsonMode) {
                options.responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
            }

            chatClientBuilder.build()
                    .prompt()
                    .options(options.build())
                    .system(systemPrompt)
                    .user(userPrompt)
                    .stream()
                    .chatResponse()
                    .timeout(Duration.ofSeconds(60))
                    .doOnNext(response -> {
                        MdcContext.wrap(tracer, logContext, () -> {
                            responseCount.incrementAndGet();

                            Generation generation = response == null ? null : response.getResult();
                            AssistantMessage output = generation == null ? null : generation.getOutput();
                            String chunk = output == null ? "" : output.getText();

                            if (response != null && response.getResults() != null) {
                                resultCount.addAndGet(response.getResults().size());
                            }
                            if (response != null && response.hasToolCalls()) {
                                toolCallResponseCount.incrementAndGet();
                            }
                            if (response != null && response.getMetadata() != null) {
                                if (response.getMetadata().getModel() != null) {
                                    responseModel.set(response.getMetadata().getModel());
                                }
                                // 只记峰值，**不逐 chunk 累加**：同一个累计 usage 会出现在多个
                                // chunk 里（兼容实现会在末尾多给一个），累加会把结果翻倍
                                TokenUsageAccumulator.trackPeak(peakUsage, response.getMetadata().getUsage());
                            }
                            if (generation != null && generation.getMetadata() != null) {
                                String finishReason = generation.getMetadata().getFinishReason();
                                if (finishReason != null && !finishReason.isBlank()) {
                                    finishReasons.add(finishReason);
                                }
                                generationMetadataKeys.addAll(generation.getMetadata().keySet());
                            }

                            if (chunk == null || chunk.isBlank()) {
                                emptyTextResponseCount.incrementAndGet();
                            } else {
                                content.append(chunk);

                                if (firstChunkLogged.compareAndSet(false, true)) {
                                    long delay =
                                            System.currentTimeMillis() - llmStart;

                                    firstChunkDelayMs.set(delay);

                                    log.info(
                                            "[PERF-WORKFLOW] llm_first_chunk step={} delayMs={} chunkChars={}",
                                            step,
                                            delay,
                                            chunk.length()
                                    );
                                }

                                safeEmitter.emitContent(
                                        step.name(),
                                        field,
                                        chunk
                                );
                                // 记录已输出字符数：重试判定用它判断「是不是已经吐过内容了」
                                emittedChars.addAndGet(chunk.length());
                            }
                        }).run();
                    })
                    .blockLast();

            // 流已正常结束（blockLast 是同步阻塞的），提交本次调用含工具调用多轮的最终累计用量
            usage.add(peakUsage.get());

            long llmDurationMs =
                    System.currentTimeMillis() - llmStart;

            log.info(
                    "[PERF-WORKFLOW] llm_end step={} durationMs={} firstChunkMs={} outputChars={}",
                    step,
                    llmDurationMs,
                    firstChunkDelayMs.get(),
                    content.length()
            );

            if (content.toString().isBlank()) {
                log.warn(
                        "[DIAG-WORKFLOW] llm_empty_response step={} model={} responseCount={} resultCount={} " +
                                "emptyTextResponses={} finishReasons={} generationMetadataKeys={} " +
                                "toolCallResponses={} promptTokens={} completionTokens={} totalTokens={}",
                        step,
                        responseModel.get(),
                        responseCount.get(),
                        resultCount.get(),
                        emptyTextResponseCount.get(),
                        finishReasons,
                        generationMetadataKeys,
                        toolCallResponseCount.get(),
                        usage.getPromptTokens(),
                        usage.getCompletionTokens(),
                        usage.getTotalTokens()
                );
                throw new RuntimeException(stepPrefix + "模型返回空内容");
            }

            return new LlmStreamResult(content.toString(), usage);
        } catch (RuntimeException e) {
            log.warn(
                    "[PERF-WORKFLOW] llm_failed step={} durationMs={} firstChunkMs={} error={}",
                    step,
                    System.currentTimeMillis() - llmStart,
                    firstChunkDelayMs.get(),
                    e.getMessage()
            );
            throw LlmErrorClassifier.wrap(stepPrefix, e);
        } catch (Exception e) {
            log.warn(
                    "[PERF-WORKFLOW] llm_failed step={} durationMs={} firstChunkMs={} error={}",
                    step,
                    System.currentTimeMillis() - llmStart,
                    firstChunkDelayMs.get(),
                    e.getMessage()
            );
            throw new RuntimeException(stepPrefix + LlmErrorClassifier.friendlyMessage(e), e);
        }
    }
}
