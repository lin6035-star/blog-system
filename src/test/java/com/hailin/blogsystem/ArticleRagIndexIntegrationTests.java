package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.rag.ArticleRagIndexService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import org.awaitility.Awaitility;

@SpringBootTest
class ArticleRagIndexIntegrationTests {

    @Autowired
    private ArticleRagIndexService articleRagIndexService;

    @Autowired
    private VectorStore vectorStore;

    @Test
    @Disabled("手动冒烟测试：连接真实 ES，会清除开发环境 source=article 的所有索引并写入 H2 测试数据。仅手动运行时取消注释。")
    void indexesPublishedArticlesAndSearchesThemFromVectorStore() {
        int indexedCount = articleRagIndexService.indexPublishedArticles();

        assertThat(indexedCount).isGreaterThan(0);

        AtomicReference<List<Document>> resultsRef = new AtomicReference<>();

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    List<Document> results = vectorStore.similaritySearch(
                            SearchRequest.builder()
                                    .query("Published content")
                                    .topK(5)
                                    .similarityThreshold(0.0)
                                    .build()
                    );

                    resultsRef.set(results);
                    assertThat(results).isNotEmpty();
                });

        assertThat(resultsRef.get())
                .anySatisfy(document -> {
                    assertThat(document.getText()).contains("Published");
                    assertThat(document.getMetadata()).containsEntry("source", "article");
                });
    }
}