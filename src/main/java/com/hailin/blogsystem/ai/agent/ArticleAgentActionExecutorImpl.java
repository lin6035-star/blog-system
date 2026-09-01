package com.hailin.blogsystem.ai.agent;

import com.hailin.blogsystem.ai.memory.AiEpisodicMemoryRetrieveService;
import com.hailin.blogsystem.ai.memory.AiMemoryRetrieveService;
import com.hailin.blogsystem.ai.rag.ArticleRagSearchService;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import com.hailin.blogsystem.entity.dto.ArticleRagSearchResult;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.EpisodicMemoryRagContext;
import com.hailin.blogsystem.entity.dto.MemoryRagContext;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.service.ArticlesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文章域只读动作执行器（V2.5）。
 *
 * - QUERY_ARTICLE：查库验归属 + 输出文章结构分析（标题/状态/字数/段落/小标题/正文预览）
 * - QUERY_MEMORY / SEARCH_RAG：与学习域同构，复用现有检索链路
 *
 * articleId 只当线索：优先后端页面上下文（PageContextDTO），其次 LLM 摘录，
 * 最终查库校验存在 + 归属，不通过抛异常（FAILED step，循环继续，不猜）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ArticleAgentActionExecutorImpl implements ArticleAgentActionExecutor {

    private static final int TITLE_MAX = 40;
    private static final int TEXT_MAX = 200;
    private static final int PREVIEW_MAX = 600;

    private final ArticlesService articlesService;
    private final AiMemoryRetrieveService aiMemoryRetrieveService;
    private final AiEpisodicMemoryRetrieveService aiEpisodicMemoryRetrieveService;
    private final ArticleRagSearchService articleRagSearchService;
    private final BlogAiProperties blogAiProperties;

    @Override
    public String execute(AgentStepDecision decision, Long userId, PageContextDTO pageContext) {
        if (userId == null) {
            return "当前未登录，无法分析文章。";
        }
        return switch (decision.actionType()) {
            case QUERY_ARTICLE -> queryArticle(decision, userId, pageContext);
            case QUERY_MEMORY -> queryMemory(decision, userId);
            case SEARCH_RAG -> searchRag(decision);
            case QUERY_LEARNING_DASHBOARD ->
                    throw new UnsupportedOperationException("学习域动作不经过文章域执行器");
            case ASK_USER, FINAL_ANSWER, SUGGEST_WORKFLOW, SUGGEST_WRITE ->
                    throw new UnsupportedOperationException("终态动作不经过执行器");
        };
    }

    /**
     * 查询当前文章 + 结构分析。
     *
     * 线索优先级：后端页面上下文 > LLM 摘录（都不信，只当定位入口）。
     * 校验失败（缺失 / 非数字 / 不存在 / 非本人）抛 IllegalArgumentException，
     * 由 Runtime 记 FAILED step + 失败 observation，循环继续由下一轮决策收尾。
     */
    private String queryArticle(AgentStepDecision decision, Long userId, PageContextDTO pageContext) {
        String clue = pageContext != null ? pageContext.getArticleId() : null;
        if (clue == null || clue.isBlank()) {
            clue = text(decision.input(), "articleId");
        }
        if (clue == null || !clue.matches("\\d+")) {
            throw new IllegalArgumentException("缺少当前文章 ID，不猜测文章。");
        }

        Articles article = articlesService.getById(Long.valueOf(clue));
        if (article == null) {
            throw new ArticleNotOwnedException("这篇文章不存在或已删除，无法为你分析。");
        }
        if (!userId.equals(article.getAuthorId())) {
            throw new ArticleNotOwnedException("这篇文章不是你的，无法为你分析或优化。");
        }
        return buildStructureSummary(article);
    }

    private String buildStructureSummary(Articles article) {
        String content = article.getContent() == null ? "" : article.getContent();
        String statusLabel = switch (article.getStatus() == null ? -1 : article.getStatus()) {
            case 0 -> "草稿";
            case 1 -> "已发布";
            case 2 -> "已隐藏";
            default -> "未知";
        };

        StringBuilder sb = new StringBuilder("当前文章分析：\n");
        sb.append("- 标题：《").append(limit(article.getTitle(), TITLE_MAX)).append("》\n");
        sb.append("- 状态：").append(statusLabel).append('\n');
        sb.append("- 字数：约 ").append(content.length()).append(" 字\n");
        sb.append("- 段落数：约 ").append(countParagraphs(content)).append(" 段\n");

        List<String> headings = extractHeadings(content);
        if (headings.isEmpty()) {
            sb.append("- 小标题结构：（无小标题，可能缺乏层次）\n");
        } else {
            sb.append("- 小标题结构：\n");
            for (String heading : headings) {
                sb.append("  - ").append(limit(heading, TITLE_MAX)).append('\n');
            }
        }
        if (article.getSummary() != null && !article.getSummary().isBlank()) {
            sb.append("- 摘要：").append(limit(article.getSummary(), TEXT_MAX)).append('\n');
        }
        String preview = limit(content.replaceAll("\\s+", " ").trim(), PREVIEW_MAX);
        if (!preview.isBlank()) {
            sb.append("- 正文预览：").append(preview).append('\n');
        }
        return sb.toString();
    }

    private int countParagraphs(String content) {
        if (content.isBlank()) {
            return 0;
        }
        int count = 0;
        for (String block : content.split("\\n\\s*\\n")) {
            if (!block.isBlank()) {
                count++;
            }
        }
        return count;
    }

    private List<String> extractHeadings(String content) {
        List<String> headings = new ArrayList<>();
        for (String line : content.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("## ")) {
                headings.add(trimmed);
            }
        }
        return headings;
    }

    /**
     * 用户记忆检索：语义记忆 + 情景记忆，按检索词召回。
     */
    private String queryMemory(AgentStepDecision decision, Long userId) {
        String question = text(decision.input(), "question");
        String keyword = text(decision.input(), "keyword");
        String query = question != null ? question : keyword;
        if (query == null || query.isBlank()) {
            query = "写作偏好";
        }

        StringBuilder sb = new StringBuilder("记忆摘要：\n");

        try {
            List<MemoryRagContext> memories = aiMemoryRetrieveService.retrieve(userId, query);
            for (MemoryRagContext memory : memories) {
                sb.append("- 语义记忆(").append(memory.memoryType()).append(")：")
                        .append(limit(memory.content(), TEXT_MAX)).append('\n');
            }
        } catch (Exception e) {
            log.warn("Agent 语义记忆检索失败，userId={}", userId, e);
            sb.append("- 语义记忆检索失败\n");
        }

        try {
            List<EpisodicMemoryRagContext> memories = aiEpisodicMemoryRetrieveService.retrieveForPrompt(
                    userId, blogAiProperties.getProjectKey(), query
            );
            for (EpisodicMemoryRagContext memory : memories) {
                sb.append("- 情景记忆(").append(memory.memoryType()).append(")：")
                        .append(limit(memory.content(), TEXT_MAX)).append('\n');
            }
        } catch (Exception e) {
            log.warn("Agent 情景记忆检索失败，userId={}", userId, e);
            sb.append("- 情景记忆检索失败\n");
        }

        return sb.toString();
    }

    /**
     * 站内文章知识检索（复用现有混合检索 + rerank 链路）。
     */
    private String searchRag(AgentStepDecision decision) {
        String keyword = text(decision.input(), "keyword");
        if (keyword == null || keyword.isBlank()) {
            return "站内检索缺少关键词，无法检索。";
        }

        AiIntent intent = new AiIntent();
        intent.setIntent("ARTICLE_SEARCH");
        intent.setKeyWord(keyword);

        ArticleRagSearchResult result = articleRagSearchService.search(keyword, intent);
        if (result.contexts() == null || result.contexts().isEmpty()) {
            return "站内没有检索到与「" + keyword + "」相关的文章知识。";
        }

        StringBuilder sb = new StringBuilder("站内文章知识检索结果（")
                .append(result.strategy()).append("）：\n");
        int index = 1;
        for (ArticleRagContext context : result.contexts()) {
            sb.append(index++).append(". 《").append(limit(context.title(), TITLE_MAX))
                    .append("》(").append(context.articleId()).append(")：")
                    .append(limit(context.content(), TEXT_MAX)).append('\n');
        }
        return sb.toString();
    }

    private String text(Map<String, Object> input, String key) {
        if (input == null) {
            return null;
        }
        Object value = input.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private String limit(String text, int max) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
