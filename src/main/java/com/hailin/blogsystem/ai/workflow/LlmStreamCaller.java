package com.hailin.blogsystem.ai.workflow;

import com.hailin.blogsystem.ai.LlmErrorClassifier;
import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.entity.dto.AiWorkflowStep;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * LLM 流式调用骨架：streamUsage + timeout + chatResponse + delta 提取过滤
 * + usage 累计 + 空内容检查 + 异常分类。
 * prompt 文本、maxTokens、emit 的 step/field 由调用点传入；
 * 后处理（cleanMarkdown / JSON 解析 / usage 落库）由调用点基于返回值做。
 */
@Component
@RequiredArgsConstructor
public class LlmStreamCaller {

    //流式调用结果：content 原始文本 + usage（含工具调用多轮累计）
    public record LlmStreamResult(String content, TokenUsageAccumulator usage) {
    }

    private final ChatClient.Builder chatClientBuilder;

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

        try {
            StringBuilder content = new StringBuilder();
            TokenUsageAccumulator usage = new TokenUsageAccumulator();

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
                        String chunk = response.getResult() == null ? "" : response.getResult().getOutput().getText();
                        if (chunk != null && !chunk.isEmpty()) {
                            content.append(chunk);
                            safeEmitter.emitContent(step.name(), field, chunk);
                        }
                        usage.add(response.getMetadata().getUsage());
                    })
                    .blockLast();

            if (content.toString().isBlank()) {
                throw new RuntimeException(stepPrefix + "模型返回空内容");
            }

            return new LlmStreamResult(content.toString(), usage);
        } catch (RuntimeException e) {
            throw LlmErrorClassifier.wrap(stepPrefix, e);
        } catch (Exception e) {
            throw new RuntimeException(stepPrefix + LlmErrorClassifier.friendlyMessage(e), e);
        }
    }
}
