package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.rag.ArticleRagIndexService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;


import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest
class ArticleRagIndexServiceTests {

    @Autowired
    private ArticleRagIndexService articleRagIndexService;

    @MockBean
    private VectorStore vectorStore;

    @Test
    void assignsStableDocumentIdsForArticleChunks() {
        int indexedCount = articleRagIndexService.indexPublishedArticles();

        assertThat(indexedCount).isGreaterThan(0);

        ArgumentCaptor<List<Document>> documentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(documentsCaptor.capture());

        List<Document> documents = documentsCaptor.getValue();
        assertThat(documents)
                .anySatisfy(document -> {
                    assertThat(document.getId()).isEqualTo("article:1:chunk:0");
                    assertThat(document.getMetadata())
                            .containsEntry("articleId", 1L)
                            .containsEntry("chunkIndex", 0);
                });
    }

    @Test
    void indexesSinglePublishedArticleAfterDeletingOldIndex() {
        int indexedCount = articleRagIndexService.indexArticle(1L);

        assertThat(indexedCount).isEqualTo(1);

        verify(vectorStore).delete(any(Filter.Expression.class));

        ArgumentCaptor<List<Document>> documentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(documentsCaptor.capture());

        List<Document> documents = documentsCaptor.getValue();

        assertThat(documents)
                .singleElement()
                .satisfies(document -> {
                    assertThat(document.getId()).isEqualTo("article:1:chunk:0");
                    assertThat(document.getText()).contains("Published Article");
                    assertThat(document.getMetadata())
                            .containsEntry("source", "article")
                            .containsEntry("articleId", 1L)
                            .containsEntry("title", "Published Article")
                            .containsEntry("chunkIndex", 0);
                });
    }

    @Test
    void doesNotIndexDraftArticle() {
        int indexedCount = articleRagIndexService.indexArticle(2L);

        assertThat(indexedCount).isZero();

        verify(vectorStore, never()).delete(any(Filter.Expression.class));
        verify(vectorStore, never()).add(anyList());
    }

    @Test
    void deletesArticleIndexByArticleIdMetadata() {
        articleRagIndexService.deleteArticleIndex(1L);

        ArgumentCaptor<Filter.Expression> captor = ArgumentCaptor.forClass(Filter.Expression.class);
        verify(vectorStore).delete(captor.capture());

        Filter.Expression expression = captor.getValue();

        assertThat(expression.type()).isEqualTo(Filter.ExpressionType.EQ);
        assertThat(expression.left()).isEqualTo(new Filter.Key("articleId"));
        assertThat(expression.right()).isEqualTo(new Filter.Value(1L));
    }

    @Test
    void rebuildDeletesOnlyArticleIndexesBeforeIndexingPublishedArticles() {
        articleRagIndexService.indexPublishedArticles();

        ArgumentCaptor<Filter.Expression> deleteCaptor = ArgumentCaptor.forClass(Filter.Expression.class);
        verify(vectorStore).delete(deleteCaptor.capture());

        Filter.Expression expression = deleteCaptor.getValue();

        assertThat(expression.type()).isEqualTo(Filter.ExpressionType.EQ);
        assertThat(expression.left()).isEqualTo(new Filter.Key("source"));
        assertThat(expression.right()).isEqualTo(new Filter.Value("article"));
    }
}
