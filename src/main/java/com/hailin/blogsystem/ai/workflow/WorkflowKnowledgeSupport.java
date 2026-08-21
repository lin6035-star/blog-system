package com.hailin.blogsystem.ai.workflow;

import com.hailin.blogsystem.ai.rag.ArticleRagSearchService;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import com.hailin.blogsystem.entity.dto.ArticleRagSearchResult;
import com.hailin.blogsystem.service.AiEpisodicMemoryService;
import com.hailin.blogsystem.service.AiUserMemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识检索基础设施：长期记忆召回 + 站内文章 RAG 检索。
 * 三个 Workflow 逐字重复的检索方法统一到这里；两者失败都兜底（记忆返回空串、RAG 返回空列表），不阻断流程。
 */
@Component
@RequiredArgsConstructor
public class WorkflowKnowledgeSupport {

    private final AiUserMemoryService aiUserMemoryService;
    private final ArticleRagSearchService articleRagSearchService;
    private final AiEpisodicMemoryService aiEpisodicMemoryService;

    //记忆检索失败兜底空串
    public String retrieveMemoryContext(Long userId, String requirement) {
        if (userId == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        try {
            String memoryPrompt = aiUserMemoryService.buildMemoryPrompt(userId, requirement);
            if (memoryPrompt != null && !memoryPrompt.isBlank()) {
                sb.append(memoryPrompt).append("\n\n---\n\n");
            }
        } catch (Exception e) {
            // 记忆检索失败不阻断 Workflow
        }

        try {
            String episodicPrompt = aiEpisodicMemoryService.buildEpisodicPrompt(userId, requirement);
            if (episodicPrompt != null && !episodicPrompt.isBlank()) {
                sb.append(episodicPrompt).append("\n\n---\n\n");
            }
        } catch (Exception e) {
            // 情景记忆检索失败不阻断 Workflow
        }

        return sb.toString().trim();
    }

    //RAG 检索失败兜底空列表，不阻断流程
    //query = 检索原文；keyword = 提取的关键词（填 AiIntent.keyword）
    public List<Map<String, Object>> retrieveRagReferences(String query, String keyword) {
        try {
            AiIntent intent = new AiIntent();
            intent.setIntent("ARTICLE_SEARCH");
            intent.setKeyWord(keyword);

            ArticleRagSearchResult result = articleRagSearchService.search(query, intent);

            if (result == null || result.contexts() == null || result.contexts().isEmpty()) {
                return new ArrayList<>();
            }

            return result.contexts().stream()
                    .map(this::toRagReference)
                    .toList();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private Map<String, Object> toRagReference(ArticleRagContext context) {
        Map<String, Object> reference = new HashMap<>();
        reference.put("articleId", context.articleId());
        reference.put("title", context.title());
        reference.put("chunkIndex", context.chunkIndex());
        reference.put("snippet", context.content());
        return reference;
    }
}
