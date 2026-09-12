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
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;
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

            int systemPromptChars =
                    systemPrompt == null ? 0 : systemPrompt.length();

            int userPromptChars =
                    userPrompt == null ? 0 : userPrompt.length();

            log.info(
                    "[PERF-WORKFLOW] llm_start step={} systemPromptChars={} userPromptChars={} maxTokens={}",
                    step,
                    systemPromptChars,
                    userPromptChars,
                    maxTokens
            );

            OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().streamUsage(true);
            if (maxTokens != null) {
                options.maxTokens(maxTokens);
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
                                usage.add(response.getMetadata().getUsage());
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
                            }
                        }).run();
                    })
                    .blockLast();

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
