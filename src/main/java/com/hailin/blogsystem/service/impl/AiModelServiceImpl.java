package com.hailin.blogsystem.service.impl;

import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.service.AiModelService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Slf4j
@Service
public class AiModelServiceImpl implements AiModelService {

    private final ChatClient chatClient;

    public AiModelServiceImpl(ChatClient.Builder chatClientBuilder, BlogAiProperties blogAiProperties) {
        this.chatClient = chatClientBuilder
                .defaultSystem(blogAiProperties.getSystemPrompt())
                .build();
    }

    @Override  //阻塞式，等ai全部回答完在全部输出
    public String chat(String message, String promptContext) {
        String context = promptContext == null || promptContext.isBlank()
                ? "无页面上下文"
                : promptContext;

        try {
            String content = chatClient.prompt()
                    .user("""
                        用户当前页面上下文：
                        %s

                        用户问题：
                        %s
                        """.formatted(context, message))
                    .call()
                    .content();

            if (content == null || content.isBlank()) {
                return "抱歉，AI 暂时没有返回有效内容，请稍后再试。";
            }

            return content;

        } catch (Exception e) {
            log.error("AI 模型调用失败",e);
            return fallbackMessage(e);
        }
    }

    @Override  //流式输出
    public Flux<String> streamChat(String message, String promptContext) {
        String context = promptContext == null || promptContext.isBlank()
                ? "无页面上下文"
                : promptContext;

        return chatClient.prompt()
                .user("""
                        用户当前页面上下文：
                        %s

                        用户问题：
                        %s
                        """.formatted(context, message))
                .stream()
                .content()
                .onErrorResume(e -> {
                    log.error("AI 流式调用失败", e);
                    return Flux.just(fallbackMessage(e));
                });
    }


    private String fallbackMessage(Throwable e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();

        if (msg.contains("401") || msg.contains("api key")) {
            return "AI 配置异常，请联系管理员检查 API Key。";
        }

        if (msg.contains("quota") || msg.contains("balance") || msg.contains("insufficient")) {
            return "AI 服务额度不足，请稍后再试。";
        }

        if (msg.contains("timeout") || msg.contains("timed out")) {
            return "AI 响应超时，请稍后重试。";
        }

        return "抱歉，AI 服务暂时不可用，请稍后再试。";
    }
}