package com.hailin.blogsystem.ai.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.json.JsonData;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import jakarta.json.JsonString;

import org.springframework.beans.factory.annotation.Value;

//ES keyWord 文本检索，作为向量检索的补充
// 使用match查询搜索 content 字段，filter source = article
// ES match全文检索，过滤source = article
@Slf4j
@Component
@RequiredArgsConstructor
public class ArticleRagKeywordRetriever {
    private static final int DEFAULT_TOP_K = 5;

    @Value("${spring.ai.vectorstore.elasticsearch.index-name:spring-ai-document-index}")
    private String indexName;

    private final ElasticsearchClient elasticsearchClient;

    public List<ArticleRagContext> retrieve(String question){
        return retrieve(question,DEFAULT_TOP_K);
    }

    public List<ArticleRagContext> retrieve(String question, int topK) {
        if (question == null || question.isBlank()) {
            return List.of();
        }

        try {
            SearchResponse<JsonData> response = elasticsearchClient.search(s -> s
                            .index(indexName)
                            .size(topK)
                            .query(q -> q
                                    .bool(b -> b
                                            .must(m -> m
                                                    .match(mm -> mm
                                                            .field("content")
                                                            .query(question.trim())
                                                            .operator(Operator.And)
                                                    )
                                            )
                                            .filter(f -> f
                                                    .term(t -> t
                                                            .field("metadata.source.keyword")
                                                            .value(FieldValue.of("article"))
                                                    )
                                            )
                                    )
                            ),
                    JsonData.class
            );

            List<ArticleRagContext> contexts = new ArrayList<>();
            response.hits().hits().forEach(hit -> {
                JsonData source = hit.source();
                if (source == null) return;

                var json = source.toJson().asJsonObject();
                var meta = json.getJsonObject("metadata");
                if (meta == null) return;

                Long articleId = toLong(meta.get("articleId"));
                String title = toString(meta.get("title"));
                Integer chunkIndex = toInt(meta.get("chunkIndex"));
                String content = json.get("content") instanceof JsonString js
                        ? js.getString() : "";

                contexts.add(new ArticleRagContext(articleId, title, chunkIndex, content));
            });

            log.info("RAG keyword 检索完成，question={}, 命中={}", question, contexts.size());
            return contexts;
        } catch (IOException e) {
            log.error("RAG keyword 检索失败，question={}", question, e);
            return List.of();
        }
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        return Long.valueOf(value.toString());
    }

    private Integer toInt(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        return Integer.valueOf(value.toString());
    }

    private String toString(Object value) {
        return value == null ? "" : value.toString();
    }
}
