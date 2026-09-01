package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.agent.AgentStepDecision;
import com.hailin.blogsystem.ai.agent.ArticleAgentActionExecutorImpl;
import com.hailin.blogsystem.ai.memory.AiEpisodicMemoryRetrieveService;
import com.hailin.blogsystem.ai.memory.AiMemoryRetrieveService;
import com.hailin.blogsystem.ai.rag.ArticleRagSearchService;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.service.ArticlesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 文章域动作执行器测试（V2.5）。
 * 验证 QUERY_ARTICLE 查库验归属（articleId 只当线索）+ 结构摘要输出 + 失败语义。
 */
class ArticleAgentActionExecutorTests {

    private ArticlesService articlesService;
    private ArticleAgentActionExecutorImpl executor;

    @BeforeEach
    void setUp() {
        articlesService = mock(ArticlesService.class);
        executor = new ArticleAgentActionExecutorImpl(
                articlesService,
                mock(AiMemoryRetrieveService.class),
                mock(AiEpisodicMemoryRetrieveService.class),
                mock(ArticleRagSearchService.class),
                new BlogAiProperties()
        );
    }

    private static PageContextDTO articleContext(String articleId) {
        PageContextDTO pageContext = new PageContextDTO();
        pageContext.setPageType("article-detail");
        pageContext.setArticleId(articleId);
        return pageContext;
    }

    @Test
    void queryArticleOwnedArticleReturnsStructureSummary() {
        Articles article = article(12L, 100L);
        when(articlesService.getById(12L)).thenReturn(article);

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE),
                100L,
                articleContext("12")
        );

        assertThat(observation).contains("《Redis 缓存设计》");
        assertThat(observation).contains("已发布");
        assertThat(observation).contains("字数");
        assertThat(observation).contains("- 小标题结构：");
        assertThat(observation).contains("## 缓存穿透");
    }

    @Test
    void queryArticlePrefersPageContextClueOverLlmExtraction() {
        // 页面上下文有 articleId 时，LLM 摘录的 articleId 不生效（后端权威优先）
        Articles article = article(12L, 100L);
        when(articlesService.getById(12L)).thenReturn(article);

        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE)
                        .withInput(Map.of("articleId", "999")),
                100L,
                articleContext("12")
        );

        assertThat(observation).contains("《Redis 缓存设计》");
    }

    @Test
    void queryArticleWithoutClueThrows() {
        assertThatThrownBy(() -> executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE),
                100L,
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少当前文章 ID");
    }

    @Test
    void queryArticleNonNumericClueThrows() {
        assertThatThrownBy(() -> executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE),
                100L,
                articleContext("abc")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少当前文章 ID");
    }

    @Test
    void queryArticleNotFoundThrows() {
        when(articlesService.getById(12L)).thenReturn(null);

        assertThatThrownBy(() -> executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE),
                100L,
                articleContext("12")
        )).isInstanceOf(com.hailin.blogsystem.ai.agent.ArticleNotOwnedException.class)
                .hasMessageContaining("文章不存在");
    }

    @Test
    void queryArticleNotOwnedThrows() {
        when(articlesService.getById(12L)).thenReturn(article(12L, 999L));

        assertThatThrownBy(() -> executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE),
                100L,
                articleContext("12")
        )).isInstanceOf(com.hailin.blogsystem.ai.agent.ArticleNotOwnedException.class)
                .hasMessageContaining("不是你的");
    }

    @Test
    void notLoggedInReturnsFriendlyText() {
        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.QUERY_ARTICLE),
                null,
                articleContext("12")
        );

        assertThat(observation).isEqualTo("当前未登录，无法分析文章。");
    }

    @Test
    void ragWithoutKeywordReturnsHint() {
        String observation = executor.execute(
                AgentStepDecision.of(AgentStepActionType.SEARCH_RAG),
                100L,
                null
        );

        assertThat(observation).contains("缺少关键词");
    }

    private Articles article(Long id, Long authorId) {
        Articles article = new Articles();
        article.setId(id);
        article.setAuthorId(authorId);
        article.setTitle("Redis 缓存设计");
        article.setSummary("缓存穿透与击穿的区别");
        article.setStatus(1);
        article.setContent("# Redis 缓存\n\n## 缓存穿透\n\n内容一\n\n## 缓存击穿\n\n内容二");
        article.setPublishedAt(LocalDateTime.now());
        return article;
    }
}
