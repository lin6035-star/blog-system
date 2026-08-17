package com.hailin.blogsystem.service.impl;

import com.hailin.blogsystem.ai.LlmErrorClassifier;
import com.hailin.blogsystem.ai.TokenUsageAccumulator;
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
import com.hailin.blogsystem.utils.UserContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AiModelServiceImpl implements AiModelService {

    //非流式降级重跑时全文切块的大小（字符），模拟流式输出
    private static final int NON_STREAMING_CHUNK_SIZE = 32;

    private final ChatClient chatClient;
    private final AiArticleTools aiArticleTools;
    private final AiNavigationToolsFactory aiNavigationToolsFactory;
    private final AiEditorToolFactory aiEditorToolFactory;
    private final AiArticleActionToolsFactory aiArticleActionToolsFactory;
    private final AiUserProfileTools aiUserProfileTools;
    private final AiLearningPlanTools aiLearningPlanTools;
    private final QueryLearningPlansTool queryLearningPlansTool;
    private final AiIntentClassifier aiIntentClassifier;


    public AiModelServiceImpl(ChatClient.Builder chatClientBuilder, BlogAiProperties blogAiProperties, AiArticleTools aiArticleTools, AiNavigationToolsFactory aiNavigationToolsFactory, AiEditorToolFactory aiEditorToolFactory, AiArticleActionToolsFactory aiArticleActionToolsFactory, AiUserProfileTools aiUserProfileTools, AiLearningPlanTools aiLearningPlanTools, QueryLearningPlansTool queryLearningPlansTool, AiIntentClassifier aiIntentClassifier) {
        this.aiArticleTools = aiArticleTools;
        this.aiEditorToolFactory = aiEditorToolFactory;
        this.aiArticleActionToolsFactory = aiArticleActionToolsFactory;
        this.aiUserProfileTools = aiUserProfileTools;
        this.aiLearningPlanTools = aiLearningPlanTools;
        this.queryLearningPlansTool = queryLearningPlansTool;
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
    public Flux<String> streamChat(AiPrompt prompt,String requestId,TokenUsageAccumulator usageAccumulator) {

        AiNavigationTools aiNavigationTools = aiNavigationToolsFactory.create(requestId);
        AiEditorTools aiEditorTools = aiEditorToolFactory.create(requestId);
        AiArticleActionTools aiArticleActionTools = aiArticleActionToolsFactory.create(requestId);

        TokenUsageAccumulator usage = usageAccumulator == null ? new TokenUsageAccumulator() : usageAccumulator;

        //工具执行在 Spring AI 内部线程池（boundedElastic），ThreadLocal 的 UserContext 拿不到。
        // 在这里（请求线程）读一次 userId，通过 ToolContext 显式传给工具；重跑路径复用同一份。
        Map<String, Object> toolContext = buildToolContext();

        return chatClient.prompt()
                .user(prompt.getFinalPromptContext())
                .tools(aiArticleTools,aiNavigationTools,aiEditorTools,aiArticleActionTools,aiUserProfileTools,aiLearningPlanTools)
                .toolCallbacks(queryLearningPlansTool)
                .toolContext(toolContext)
                .options(OpenAiChatOptions.builder()
                        .streamUsage(true)
                        .build())
                .stream()
                .chatResponse()
                .timeout(Duration.ofSeconds(60))
                .map(response -> {
                    //工具调用是多轮请求，每轮一个 usage，跨轮累计
                    usage.add(response.getMetadata().getUsage());
                    return response.getResult() == null ? "" : response.getResult().getOutput().getText();
                })
                .filter(text -> text != null && !text.isEmpty())
                .onErrorResume(e -> {
                    if (isStreamingToolAggregationFailure(e)) {
                        //qwen 流式 tool_calls 分片中 name 会置空/缺失，Spring AI 1.0.x 聚合器合并后
                        // toolName/toolInput 为空触发断言炸流。此时 LLM 尚未输出任何文本（第一轮全是工具调用），
                        // 降级非流式重跑：工具循环在非流式路径正常，最终文本切块模拟流式，用户无感知。
                        log.warn("流式工具调用聚合失败，降级非流式重跑: {}", e.getMessage());
                        return retryNonStreamingWithTools(prompt, requestId, usage, toolContext);
                    }
                    log.error("AI 流式调用失败", e);
                    return Flux.just(fallbackMessage(e));
                });
    }

    //构建传给工具的 ToolContext：userId 在请求线程读取（工具执行线程读不到 ThreadLocal）
    private Map<String, Object> buildToolContext() {
        Map<String, Object> context = new HashMap<>();
        context.put("userId", UserContext.get());
        return context;
    }

    //Spring AI 1.0.x 内部断言文案（MethodToolCallback / DelegatingToolCallbackResolver），版本升级可能变化
    private boolean isStreamingToolAggregationFailure(Throwable e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("toolName cannot be null or empty")
                || msg.contains("toolInput cannot be null or empty"));
    }

    //非流式重跑：tool_calls 在非流式响应里是完整 JSON，无分片聚合问题。
    // 拿到最终全文后按固定长度切块 emit，保持 SSE 流式输出形态（前端无感知）。
    private Flux<String> retryNonStreamingWithTools(AiPrompt prompt, String requestId, TokenUsageAccumulator usage, Map<String, Object> toolContext) {
        AiNavigationTools aiNavigationTools = aiNavigationToolsFactory.create(requestId);
        AiEditorTools aiEditorTools = aiEditorToolFactory.create(requestId);
        AiArticleActionTools aiArticleActionTools = aiArticleActionToolsFactory.create(requestId);
        try {
            ChatResponse response = chatClient.prompt()
                    .user(prompt.getFinalPromptContext())
                    .tools(aiArticleTools,aiNavigationTools,aiEditorTools,aiArticleActionTools,aiUserProfileTools,aiLearningPlanTools)
                    .toolCallbacks(queryLearningPlansTool)
                    .toolContext(toolContext)
                    .call()
                    .chatResponse();

            usage.add(response.getMetadata() == null ? null : response.getMetadata().getUsage());

            String content = response.getResult() == null || response.getResult().getOutput() == null
                    ? "" : response.getResult().getOutput().getText();
            if (content == null || content.isBlank()) {
                return Flux.just("抱歉，AI 暂时没有返回有效内容，请稍后再试。");
            }

            List<String> chunks = new ArrayList<>();
            for (int i = 0; i < content.length(); i += NON_STREAMING_CHUNK_SIZE) {
                chunks.add(content.substring(i, Math.min(content.length(), i + NON_STREAMING_CHUNK_SIZE)));
            }
            return Flux.fromIterable(chunks);
        } catch (Exception ex) {
            log.error("非流式工具调用重跑失败", ex);
            return Flux.just(fallbackMessage(ex));
        }
    }


    private String fallbackMessage(Throwable e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();

        if (msg.contains("401") || msg.contains("api key")) {
            return "AI 配置异常，请联系管理员检查 API Key。";
        }

        if (msg.contains("quota") || msg.contains("balance") || msg.contains("insufficient")) {
            return "AI 服务额度不足，请稍后再试。";
        }

        if (msg.contains("timeout") || msg.contains("timed out")
                || LlmErrorClassifier.containsTimeoutException(e)) {
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
