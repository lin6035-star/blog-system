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
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
    private final AiIntentClassifier aiIntentClassifier;
    private final AiLearningDashboardTool aiLearningDashboardTool;

    /**
     * 普通聊天的输出上限。
     *
     * <p>除了防跑飞，它还是<b>计费预扣的依据</b>——没有显式 maxTokens 时
     * 「本次调用最多花多少」就是不可知的，预扣只能靠猜（设计稿 §5.3 明确反对按 P95 估）。
     * 见 {@code AiChatBillingSupport}。
     */
    private final int chatMaxTokens;


    public AiModelServiceImpl(ChatClient.Builder chatClientBuilder, BlogAiProperties blogAiProperties, AiArticleTools aiArticleTools, AiNavigationToolsFactory aiNavigationToolsFactory, AiEditorToolFactory aiEditorToolFactory, AiArticleActionToolsFactory aiArticleActionToolsFactory, AiUserProfileTools aiUserProfileTools, AiIntentClassifier aiIntentClassifier, AiLearningDashboardTool aiLearningDashboardTool) {
        this.aiArticleTools = aiArticleTools;
        this.aiEditorToolFactory = aiEditorToolFactory;
        this.aiArticleActionToolsFactory = aiArticleActionToolsFactory;
        this.aiUserProfileTools = aiUserProfileTools;
        this.aiIntentClassifier = aiIntentClassifier;
        this.aiLearningDashboardTool = aiLearningDashboardTool;
        this.chatClient = chatClientBuilder
                .defaultSystem(blogAiProperties.getSystemPrompt())
                .build();
        this.aiNavigationToolsFactory = aiNavigationToolsFactory;
        this.chatMaxTokens = blogAiProperties.getBilling().getChatMaxTokens();
    }


    @Override
    public Flux<String> streamChat(AiPrompt prompt,String requestId,TokenUsageAccumulator usageAccumulator) {

        AiNavigationTools aiNavigationTools = aiNavigationToolsFactory.create(requestId);
        AiEditorTools aiEditorTools = aiEditorToolFactory.create(requestId);
        AiArticleActionTools aiArticleActionTools = aiArticleActionToolsFactory.create(requestId);

        TokenUsageAccumulator usage = usageAccumulator == null ? new TokenUsageAccumulator() : usageAccumulator;

        // 单次流式调用的 usage 跟踪：只记峰值，流结束时统一提交一次（原因见 TokenUsageAccumulator.trackPeak）
        AtomicReference<Usage> peakUsage = new AtomicReference<>();
        // 提交只做一次：doOnComplete 负责正常路径，doFinally 兜底 cancel
        AtomicBoolean usageCommitted = new AtomicBoolean(false);
        Runnable commitUsage = () -> {
            if (usageCommitted.compareAndSet(false, true)) {
                usage.add(peakUsage.get());
            }
        };

        //工具执行在 Spring AI 内部线程池（boundedElastic），ThreadLocal 的 UserContext 拿不到。
        // 在这里（请求线程）读一次 userId，通过 ToolContext 显式传给工具；重跑路径复用同一份。
        Map<String, Object> toolContext = buildToolContext(prompt);

        Object[] tools = buildTools(
                prompt,
                aiNavigationTools,
                aiEditorTools,
                aiArticleActionTools
        );

        return chatClient.prompt()
                .user(prompt.getFinalPromptContext())
                .tools(tools)
                .toolCallbacks(buildToolCallbacks(prompt))
                .toolContext(toolContext)
                .options(OpenAiChatOptions.builder()
                        .maxTokens(chatMaxTokens)
                        .streamUsage(true)
                        .build())
                .stream()
                .chatResponse()
                .timeout(Duration.ofSeconds(60))
                .map(response -> {
                    // 只记峰值，**不在这里累加**：同一个"累计 usage"会出现在多个 chunk 里
                    // （OpenAI 规范只在流末尾补一个收尾 chunk，兼容实现常常多给一个），
                    // 逐 chunk 累加会把结果成倍放大（实测 ×2）。累计值单调不减，取峰值即取最终值
                    TokenUsageAccumulator.trackPeak(peakUsage, usageOf(response));
                    // 工具调用轮 result 可能没有文本（getText() 为 null），
                    // Reactor map 不允许 null 值，必须归一为 ""（后续 filter 会去掉）。
                    String text = response.getResult() == null
                            || response.getResult().getOutput() == null
                            || response.getResult().getOutput().getText() == null
                            ? "" : response.getResult().getOutput().getText();
                    return text;
                })
                .filter(text -> text != null && !text.isEmpty())
                .onErrorResume(e -> {
                    if (isStreamingToolAggregationFailure(e)) {
                        //qwen 流式 tool_calls 分片中 name 会置空/缺失，Spring AI 1.0.x 聚合器合并后
                        // toolName/toolInput 为空触发断言炸流。此时 LLM 尚未输出任何文本（第一轮全是工具调用），
                        // 降级非流式重跑：工具循环在非流式路径正常，最终文本切块模拟流式，用户无感知。
                        log.warn("流式工具调用聚合失败，降级非流式重跑: {}", e.getMessage());
                        return retryNonStreamingWithTools(prompt, requestId, peakUsage, toolContext);
                    }
                    log.error("AI 流式调用失败", e);
                    return Flux.just(fallbackMessage(e));
                })
                // **提交必须挂 doOnComplete**：它的回调在 onComplete **传播给下游之前**执行，
                // 这样 `Flux.concat(前置事件, dataStream, stopEvent)` 里那个负责落库的
                // stopEvent 才读得到。
                //
                // ⚠️ 只挂 doFinally 会静默失效：`doFinally` 的回调在 onComplete **传播之后**才跑，
                // 落库那一刻读到的永远是 0。实测踩过——而且同步流的单测会**假绿**
                // （同步路径下 doFinally 恰好赶在前面），线上两条消息的 token_count 直接变 0。
                //
                // doFinally 保留作 cancel 兜底：用户中途关页面时 token 已经消耗，也该计上。
                // 两者靠 AtomicBoolean 保证只提交一次
                .doOnComplete(commitUsage)
                .doFinally(signalType -> commitUsage.run());
    }

    /** 安全取 usage：metadata 可能为 null（原先直接 .getMetadata().getUsage() 会 NPE） */
    private Usage usageOf(ChatResponse response) {
        return response == null || response.getMetadata() == null
                ? null : response.getMetadata().getUsage();
    }

    /**
     * 构建传给工具的 ToolContext：userId / sessionId 在请求线程读取（工具执行线程读不到 ThreadLocal）。
     */
    private Map<String, Object> buildToolContext(AiPrompt prompt) {
        Map<String, Object> context = new HashMap<>();

        // 游客没有登录态，UserContext 为 null——null value 会被 Spring AI
        // ChatClient 校验拒绝（toolContext values cannot contain null elements）。
        // 只有非 null 才放入；工具侧取不到 userId 时自行降级。
        Long userId = UserContext.get();
        if (userId != null) {
            context.put("userId", userId);
        }

        if (prompt != null && prompt.getSessionId() != null) {
            context.put("sessionId", prompt.getSessionId());
        }

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
    private Flux<String> retryNonStreamingWithTools(AiPrompt prompt, String requestId,
                                                    AtomicReference<Usage> peakUsage, Map<String, Object> toolContext) {
        AiNavigationTools aiNavigationTools = aiNavigationToolsFactory.create(requestId);
        AiEditorTools aiEditorTools = aiEditorToolFactory.create(requestId);
        AiArticleActionTools aiArticleActionTools = aiArticleActionToolsFactory.create(requestId);
        try {

            Object[] tools = buildTools(
                    prompt,
                    aiNavigationTools,
                    aiEditorTools,
                    aiArticleActionTools
            );

            ChatResponse response = chatClient.prompt()
                    .user(prompt.getFinalPromptContext())
                    .tools(tools)
                    .toolCallbacks(buildToolCallbacks(prompt))
                    .toolContext(toolContext)
                    .options(OpenAiChatOptions.builder()
                            .maxTokens(chatMaxTokens)
                            .build())
                    .call()
                    .chatResponse();

            // 只记峰值，由外层的 doFinally 统一提交。
            // **这里不能直接 add**：降级重跑的 Flux 会正常完成，外层 doFinally 也会执行，那样就加两次
            TokenUsageAccumulator.trackPeak(peakUsage, usageOf(response));

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


    private Object[] buildTools(
            AiPrompt prompt,
            AiNavigationTools aiNavigationTools,
            AiEditorTools aiEditorTools,
            AiArticleActionTools aiArticleActionTools
    ) {
        if (prompt != null && prompt.isLearningDashboardToolEnabled()) {
            return new Object[]{};
        }

        if (prompt != null && prompt.isArticleToolsEnabled()) {
            return new Object[]{
                    aiArticleTools,
                    aiNavigationTools,
                    aiEditorTools,
                    aiArticleActionTools,
                    aiUserProfileTools
            };
        }

        return new Object[]{
                aiNavigationTools,
                aiEditorTools,
                aiArticleActionTools,
                aiUserProfileTools
        };
    }


    private ToolCallback[] buildToolCallbacks(AiPrompt prompt) {
        if (prompt != null && prompt.isLearningDashboardToolEnabled()) {
            return new ToolCallback[]{aiLearningDashboardTool};
        }
        return new ToolCallback[]{};
    }


    /*
    * RAG 检索
        -> 拼成知识库上下文
        -> 追加到 finalPromptContext
        -> 交给 ChatClient
    * */

}
