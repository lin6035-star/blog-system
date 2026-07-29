package com.hailin.blogsystem.service.impl;

import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.AiMessages;
import com.hailin.blogsystem.entity.AiPrompt;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.service.AiConversationMemoryService;
import com.hailin.blogsystem.service.AiPromptService;
import com.hailin.blogsystem.service.ArticlesService;
import com.hailin.blogsystem.utils.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AiPromptServiceImpl implements AiPromptService {

    @Autowired
    private AiConversationMemoryService aiConversationMemoryService;
    @Autowired
    private ArticlesService articlesService;
    @Autowired
    private BlogAiProperties blogAiProperties;

    @Override
    public AiPrompt buildPrompt(String userMessage, PageContextDTO pageContext, Long sessionId) {
        StringBuilder sb = new StringBuilder();

        // ① 拼对话历史（登录用户 + 有 sessionId + memory 开关开启）
        Long userId = UserContext.get();
        BlogAiProperties.Memory memoryConfig = blogAiProperties.getMemory();
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

            if ("article-detail".equals(pageContext.getPageType())
                    && pageContext.getArticleId() != null
                    && !pageContext.getArticleId().isBlank()) {

                /*Long articleId;
                try {
                    articleId = Long.valueOf(pageContext.getArticleId());
                } catch (NumberFormatException e) {
                    sb.append("文章ID格式错误，无法读取文章内容。\n");
                    return finishPrompt(sb, userMessage);
                }

                Articles article = articlesService.lambdaQuery()
                        .select(Articles::getId,
                                Articles::getTitle,
                                Articles::getSummary,
                                Articles::getContent)
                        .eq(Articles::getId, articleId)
                        .eq(Articles::getStatus, BlogConstants.ArticlesStatus.PUBLISHED)
                        .one();

                if (article == null) {
                    sb.append("当前文章不存在或未发布。\n");
                } else {
                    sb.append("\n当前文章内容：\n");
                    sb.append("标题：").append(article.getTitle()).append("\n");
                    if (article.getSummary() != null && !article.getSummary().isBlank()) {
                        sb.append("摘要：").append(article.getSummary()).append("\n");
                    }
                    sb.append("正文：\n").append(limitText(article.getContent(), 8000)).append("\n");
                }*/
            }
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

    /** 截断文本，防止文章太长撑爆 prompt */
    private String limitText(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "\n\n[文章内容过长，后半部分已省略]";
    }
}
