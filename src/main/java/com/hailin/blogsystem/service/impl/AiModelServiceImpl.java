package com.hailin.blogsystem.service.impl;

import com.hailin.blogsystem.ai.rag.ArticleRagPromptBuilder;
import com.hailin.blogsystem.ai.rag.ArticleRagRetrieveService;
import com.hailin.blogsystem.ai.tool.*;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.AiPrompt;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import com.hailin.blogsystem.service.AiIntentClassifier;
import com.hailin.blogsystem.service.AiModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

@Slf4j
@Service
public class AiModelServiceImpl implements AiModelService {

    private final ChatClient chatClient;
    private final AiArticleTools aiArticleTools;
    private final AiNavigationToolsFactory aiNavigationToolsFactory;
    private final AiEditorToolFactory aiEditorToolFactory;
    private final AiArticleActionToolsFactory aiArticleActionToolsFactory;
    private final AiUserProfileTools aiUserProfileTools;
    private final AiIntentClassifier aiIntentClassifier;


    public AiModelServiceImpl(ChatClient.Builder chatClientBuilder, BlogAiProperties blogAiProperties, AiArticleTools aiArticleTools, AiNavigationToolsFactory aiNavigationToolsFactory, AiEditorToolFactory aiEditorToolFactory, AiArticleActionToolsFactory aiArticleActionToolsFactory, AiUserProfileTools aiUserProfileTools, AiIntentClassifier aiIntentClassifier) {
        this.aiArticleTools = aiArticleTools;
        this.aiEditorToolFactory = aiEditorToolFactory;
        this.aiArticleActionToolsFactory = aiArticleActionToolsFactory;
        this.aiUserProfileTools = aiUserProfileTools;
        this.aiIntentClassifier = aiIntentClassifier;
        this.chatClient = chatClientBuilder
                .defaultSystem(blogAiProperties.getSystemPrompt())
                .build();
        this.aiNavigationToolsFactory = aiNavigationToolsFactory;
    }

    // 【已废弃】非流式接口，前端已全面切到流式，暂时注释，后续删除
    // @Override
    // public String chat(AiPrompt prompt,String requestId) {
    //     try {
    //         AiNavigationTools aiNavigationTools = aiNavigationToolsFactory.create(requestId);
    //         AiEditorTools aiEditorTools = aiEditorToolFactory.create(requestId);
    //         AiArticleActionTools aiArticleActionTools = aiArticleActionToolsFactory.create(requestId);
    //
    //
    //         String content = chatClient.prompt()
    //                 .user(prompt.getFinalPromptContext())
    //                 .tools(aiArticleTools,aiNavigationTools,aiEditorTools,aiArticleActionTools,aiUserProfileTools)
    //                 .call()
    //                 .content();
    //
    //         if (content == null || content.isBlank()) {
    //             return "抱歉，AI 暂时没有返回有效内容，请稍后再试。";
    //         }
    //
    //         return content;
    //
    //     } catch (Exception e) {
    //         log.error("AI 模型调用失败", e);
    //         return fallbackMessage(e);
    //     }
    // }

    @Override
    public Flux<String> streamChat(AiPrompt prompt,String requestId) {

        AiNavigationTools aiNavigationTools = aiNavigationToolsFactory.create(requestId);
        AiEditorTools aiEditorTools = aiEditorToolFactory.create(requestId);
        AiArticleActionTools aiArticleActionTools = aiArticleActionToolsFactory.create(requestId);

        return chatClient.prompt()
                .user(prompt.getFinalPromptContext())
                .tools(aiArticleTools,aiNavigationTools,aiEditorTools,aiArticleActionTools,aiUserProfileTools)
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

    /*
    * RAG 检索
        -> 拼成知识库上下文
        -> 追加到 finalPromptContext
        -> 交给 ChatClient
    * */

}
