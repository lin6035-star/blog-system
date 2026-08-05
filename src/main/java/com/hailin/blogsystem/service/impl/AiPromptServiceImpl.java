package com.hailin.blogsystem.service.impl;

import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.AiMessages;
import com.hailin.blogsystem.entity.AiPrompt;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.service.AiConversationMemoryService;
import com.hailin.blogsystem.service.AiPromptService;
import com.hailin.blogsystem.service.AiUserMemoryService;
import com.hailin.blogsystem.utils.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AiPromptServiceImpl implements AiPromptService {

    @Autowired
    private AiConversationMemoryService aiConversationMemoryService;
    @Autowired
    private BlogAiProperties blogAiProperties;
    @Autowired
    private AiUserMemoryService aiUserMemoryService;

    @Override
    public AiPrompt buildPrompt(String userMessage, PageContextDTO pageContext, Long sessionId) {
        StringBuilder sb = new StringBuilder();

        // ① 拼对话历史（登录用户 + 有 sessionId + memory 开关开启）
        Long userId = UserContext.get();
        BlogAiProperties.Memory memoryConfig = blogAiProperties.getMemory();
        //拿到 userId 和 memoryConfig 后，先插入长期记忆
        if (userId != null && memoryConfig.isEnabled()) {
            String longTermMemoryPrompt = aiUserMemoryService.buildMemoryPrompt(userId,userMessage);
            if (longTermMemoryPrompt != null && !longTermMemoryPrompt.isBlank()) {
                sb.append(longTermMemoryPrompt).append("\n\n---\n\n");
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
