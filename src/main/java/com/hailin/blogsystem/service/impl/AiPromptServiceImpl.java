package com.hailin.blogsystem.service.impl;

import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.AiMessages;
import com.hailin.blogsystem.entity.AiPrompt;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.service.*;
import com.hailin.blogsystem.utils.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class AiPromptServiceImpl implements AiPromptService {

    /** 单次记忆召回的兜底超时（秒）——超时按「无记忆」处理，不阻塞对话。 */
    private static final long MEMORY_RECALL_TIMEOUT_SECONDS = 3;

    @Autowired
    private AiConversationMemoryService aiConversationMemoryService;
    @Autowired
    private BlogAiProperties blogAiProperties;
    @Autowired
    private AiUserMemoryService aiUserMemoryService;
    @Autowired
    private AiEpisodicMemoryService aiEpisodicMemoryService;
    @Autowired
    private AiConversationSummaryService aiConversationSummaryService;
    @Autowired
    @Qualifier("memoryRecallExecutor")
    private Executor memoryRecallExecutor;

    @Override
    public AiPrompt buildPrompt(String userMessage, PageContextDTO pageContext, Long sessionId) {
        StringBuilder sb = new StringBuilder();

        // ① 拼对话历史（登录用户 + 有 sessionId + memory 开关开启）
        Long userId = UserContext.get();
        BlogAiProperties.Memory memoryConfig = blogAiProperties.getMemory();
        //拿到 userId 和 memoryConfig 后，先插入该会话压缩，长期记忆
        if (userId != null && sessionId != null && memoryConfig.isEnabled()) {
            String conversationSummaryPrompt = aiConversationSummaryService.buildSummaryPrompt(userId, sessionId);
            if (conversationSummaryPrompt != null && !conversationSummaryPrompt.isBlank()) {
                sb.append(conversationSummaryPrompt).append("\n\n---\n\n");
            }
        }
        if (userId != null && memoryConfig.isEnabled()) {
            /*
             * V4.5：两次记忆召回**并行**（原来串行）。
             *
             * 实测首字前 buildPrompt 占 3.6s，大头就是这两次向量召回——每次都要
             * 算 query embedding + 查 ES。并行后总耗时 ≈ 两者的 max 而非 sum。
             *
             * 超时与异常都 fail-open：记忆召回失败不该阻塞对话。
             * 召回链路只依赖传入的 userId，不碰线程绑定的 UserContext，故可安全切线程。
             */
            CompletableFuture<String> longTermFuture = CompletableFuture
                    .supplyAsync(() -> aiUserMemoryService.buildMemoryPrompt(userId, userMessage),
                            memoryRecallExecutor)
                    .orTimeout(MEMORY_RECALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .exceptionally(e -> {
                        log.warn("长期记忆召回失败（不阻塞对话）: userId={}", userId, e);
                        return "";
                    });
            CompletableFuture<String> episodicFuture = CompletableFuture
                    .supplyAsync(() -> aiEpisodicMemoryService.buildEpisodicPrompt(userId, userMessage),
                            memoryRecallExecutor)
                    .orTimeout(MEMORY_RECALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .exceptionally(e -> {
                        log.warn("情景记忆召回失败（不阻塞对话）: userId={}", userId, e);
                        return "";
                    });

            // 拼接顺序保持与原实现一致（长期记忆在前、情景记忆在后），prompt 结构不变
            String longTermMemoryPrompt = longTermFuture.join();
            if (longTermMemoryPrompt != null && !longTermMemoryPrompt.isBlank()) {
                sb.append(longTermMemoryPrompt).append("\n\n---\n\n");
            }

            String episodicMemoryPrompt = episodicFuture.join();
            if (episodicMemoryPrompt != null && !episodicMemoryPrompt.isBlank()) {
                sb.append(episodicMemoryPrompt).append("\n\n---\n\n");
            }
        }

        if (userId != null && sessionId != null && memoryConfig.isEnabled()) {
            List<AiMessages> history = aiConversationMemoryService.getRecentMessages(
                    sessionId, memoryConfig.getMaxMessages());
            if (history != null && !history.isEmpty()) {
                sb.append("## 对话历史\n");
                for (AiMessages msg : history) {
                    String roleLabel = "user".equals(msg.getRole()) ? "用户" : "AI";
                    sb.append(roleLabel).append("：").append(removeLegacyNavigationMarker(msg.getContent())).append("\n");
                }
                sb.append("\n---\n\n");
            }
        }

        // ② 拼页面上下文
        sb.append("## 页面上下文\n");
        if (pageContext == null) {
            sb.append("无页面上下文\n");
        } else {
            sb.append("页面类型：").append(pageContext.getPageType()).append("\n");
            sb.append("页面路径：").append(pageContext.getPath()).append("\n");

            // 当前文章内容已迁移至 AiMessageServiceImpl.buildArticleDetailContextFromIntent，此处不再拼接
        }

        return finishPrompt(sb, userMessage);
    }

    // ================================================================
    // Private helpers
    // ================================================================

    private AiPrompt finishPrompt(StringBuilder sb, String userMessage) {
        sb.append("\n## 当前问题\n");
        sb.append(userMessage);

        return AiPrompt.builder()
                .finalPromptContext(sb.toString())
                .userMessage(userMessage)
                .build();
    }

    private String removeLegacyNavigationMarker(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replaceAll("\\[BLOGNAV:[^\\]]+\\]", "").trim();
    }

}
