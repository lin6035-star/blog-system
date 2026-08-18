package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.rag.ArticleRagKeywordRetriever;
import com.hailin.blogsystem.ai.rag.ArticleRagRanker;
import com.hailin.blogsystem.ai.rag.ArticleRagRetrieveService;
import com.hailin.blogsystem.ai.rag.ArticleRagSearchService;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import com.hailin.blogsystem.entity.dto.ArticleRagSearchResult;
import com.hailin.blogsystem.entity.dto.ArticleRagSearchStrategy;
import com.hailin.blogsystem.service.ArticlesService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArticleRagSearchServiceTests {

    private final ArticleRagRetrieveService retrieveService = mock(ArticleRagRetrieveService.class);
    private final ArticleRagRanker ranker = mock(ArticleRagRanker.class);
    private final ArticlesService articlesService = mock(ArticlesService.class);
    private final ArticleRagKeywordRetriever keywordRetriever = mock(ArticleRagKeywordRetriever.class);
    private final ArticleRagSearchService searchService = new ArticleRagSearchService(
            retrieveService,
            ranker,
            articlesService,
            keywordRetriever
    );

    @Test
    void skipsArticleRagForNonArticleSearchIntent() {
        AiIntent intent = new AiIntent();
        intent.setIntent("GENERAL_CHAT");

        ArticleRagSearchResult result = searchService.search("你好，介绍一下你自己", intent);

        assertThat(result.strategy()).isEqualTo(ArticleRagSearchStrategy.EMPTY);
        assertThat(result.contexts()).isEmpty();
        verify(retrieveService, never()).retrieve(anyString());
        verify(keywordRetriever, never()).retrieve(anyString());
        verify(ranker, never()).rank(anyString(), any());
        verify(articlesService, never()).getArticles(any(), any(), any(), any(), any());
    }

    @Test
    void usesHybridRetrievalForArticleSearchIntent() {
        AiIntent intent = new AiIntent();
        intent.setIntent("ARTICLE_SEARCH");
        intent.setKeyWord("Redis");

        List<ArticleRagContext> vectorContexts = List.of(
                new ArticleRagContext(1L, "Redis 实战", 0, "Redis 可以用于缓存。")
        );
        List<ArticleRagContext> keywordContexts = List.of(
                new ArticleRagContext(2L, "Redis 秒杀", 0, "Redis 可以用于秒杀库存扣减。")
        );
        List<ArticleRagContext> rankedContexts = List.of(vectorContexts.get(0), keywordContexts.get(0));

        when(retrieveService.retrieve("有没有关于 Redis 的文章")).thenReturn(vectorContexts);
        when(keywordRetriever.retrieve("Redis")).thenReturn(keywordContexts);
        when(ranker.rank("Redis", rankedContexts)).thenReturn(rankedContexts);

        ArticleRagSearchResult result = searchService.search("有没有关于 Redis 的文章", intent);

        assertThat(result.strategy()).isEqualTo(ArticleRagSearchStrategy.HYBRID);
        assertThat(result.contexts()).containsExactlyElementsOf(rankedContexts);
        verify(retrieveService).retrieve("有没有关于 Redis 的文章");
        verify(keywordRetriever).retrieve("Redis");
        verify(ranker).rank("Redis", rankedContexts);
    }
}
