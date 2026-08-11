package com.hailin.blogsystem.utils;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

@SpringBootTest
class AiVectorStoreSmokeTests {

    @Autowired
    private VectorStore vectorStore;

    @Test
    void addAndSearchDocument() {
        Document doc = Document.builder()
                .text("Redis 可以用来做缓存、排行榜、点赞状态和浏览量统计。")
                .metadata("source", "smoke-test")
                .build();

        vectorStore.add(List.of(doc));

        AtomicReference<List<Document>> resultsRef = new AtomicReference<>();

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    List<Document> results = vectorStore.similaritySearch(
                            SearchRequest.builder()
                                    .query("Redis 缓存 排行榜 点赞 浏览量")
                                    .topK(5)
                                    .similarityThreshold(0.0)
                                    .build()
                    );

                    resultsRef.set(results);
                    assertThat(results).isNotEmpty();
                });

        resultsRef.get().forEach(item -> System.out.println(item.getText()));
    }
}