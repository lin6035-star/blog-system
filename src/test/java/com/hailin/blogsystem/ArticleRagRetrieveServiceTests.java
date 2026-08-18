package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.rag.ArticleRagRetrieveService;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArticleRagRetrieveServiceTests {

    private final BlogAiProperties blogAiProperties = new BlogAiProperties();
    private final VectorStore vectorStore = mock(VectorStore.class);
    private final ArticleRagRetrieveService retrieveService = new ArticleRagRetrieveService(blogAiProperties, vectorStore);

    @Test
    void returnsEmptyListWhenQuestionIsBlank() {
        List<ArticleRagContext> contexts = retrieveService.retrieve(" ");

        assertThat(contexts).isEmpty();
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void convertsArticleDocumentsToRagContexts() {
        Document document = new Document(
                "Redis 可以用来做缓存、排行榜、点赞状态和浏览量统计。",
                Map.of(
                        "source", "article",
                        "articleId", 1L,
                        "title", "Redis 实战",
                        "chunkIndex", 0
                )
        );

        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document));

        List<ArticleRagContext> contexts = retrieveService.retrieve("Redis 能做什么");

        assertThat(contexts).hasSize(1);

        ArticleRagContext context = contexts.get(0);
        assertThat(context.articleId()).isEqualTo(1L);
        assertThat(context.title()).isEqualTo("Redis 实战");
        assertThat(context.chunkIndex()).isZero();
        assertThat(context.content()).contains("Redis 可以用来做缓存");
    }

    @Test
    void ignoresNonArticleDocuments() {
        Document document = new Document(
                "smoke test document",
                Map.of("source", "smoke-test")
        );

        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document));

        List<ArticleRagContext> contexts = retrieveService.retrieve("Redis");

        assertThat(contexts).isEmpty();
    }

    @Test
    void restrictsVectorSearchToArticleDocuments() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        retrieveService.retrieve("Redis");

        var captor = forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());

        SearchRequest searchRequest = captor.getValue();
        assertThat(searchRequest.hasFilterExpression()).isTrue();

        Filter.Expression expression = searchRequest.getFilterExpression();
        assertThat(expression.type()).isEqualTo(Filter.ExpressionType.EQ);
        assertThat(expression.left()).isEqualTo(new Filter.Key("source"));
        assertThat(expression.right()).isEqualTo(new Filter.Value("article"));
    }
}
