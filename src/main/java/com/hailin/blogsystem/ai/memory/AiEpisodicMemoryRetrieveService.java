package com.hailin.blogsystem.ai.memory;

import com.hailin.blogsystem.entity.dto.EpisodicMemoryExtractResult;
import com.hailin.blogsystem.entity.dto.EpisodicMemoryRagContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@Service
public class AiEpisodicMemoryRetrieveService {

    private static final int PROMPT_TOP_K = 5;
    private static final int PROMPT_LIMIT = 3;
    private static final int DEDUPE_TOP_K = 3;
    private static final double PROMPT_SIMILARITY_THRESHOLD = 0.58;
    private static final double DEDUPE_SIMILARITY_THRESHOLD = 0.85;

    private final VectorStore vectorStore;

    public List<EpisodicMemoryRagContext> retrieveForPrompt(Long userId,
                                                                                        String projectKey, String question){
        if (userId == null || projectKey == null || projectKey.isBlank()
                || question == null || question.isBlank()) {
            return List.of();
        }

        try{
            List<Document> documents = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(question.trim())
                            .topK(PROMPT_TOP_K)
                            .similarityThreshold(PROMPT_SIMILARITY_THRESHOLD)
                            .filterExpression(episodicFilter(userId, projectKey))
                            .build()
            );

            if (documents == null || documents.isEmpty()) {
                return List.of();
            }

            List<EpisodicMemoryRagContext> contexts = documents.stream()
                    .filter(this::isEpisodicDocument)
                    .filter(document -> userId.equals(toLong(document.getMetadata().get("userId"))))
                    .filter(document -> projectKey.equals(toStringValue(document.getMetadata().get("projectKey"))))
                    .map(this::toContext)
                    .sorted(Comparator.comparingInt(this::importanceOrDefault).reversed())
                    .limit(PROMPT_LIMIT)
                    .toList();

            log.info("Episodic Memory 召回完成，userId={}, projectKey={}, count={}",
                    userId, projectKey, contexts.size());

            return contexts;

        } catch (Exception e) {
            log.warn("Episodic Memory 召回失败，userId={}, projectKey={}", userId, projectKey, e);
            return List.of();
        }
    }

    public List<EpisodicMemoryRagContext> retrieveForDedupe(Long userId, String projectKey, String content){
        if (userId == null || projectKey == null || projectKey.isBlank()
                || content == null || content.isBlank()) {
            return List.of();
        }

        try {
            List<Document> documents = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(content.trim())
                            .topK(DEDUPE_TOP_K)
                            .similarityThreshold(DEDUPE_SIMILARITY_THRESHOLD)
                            .filterExpression(episodicFilter(userId, projectKey))
                            .build()
            );

            if (documents == null || documents.isEmpty()) {
                return List.of();
            }

            return documents.stream()
                    .filter(this::isEpisodicDocument)
                    .filter(document -> userId.equals(toLong(document.getMetadata().get("userId"))))
                    .filter(document -> projectKey.equals(toStringValue(document.getMetadata().get("projectKey"))))
                    .map(this::toContext)
                    .toList();
        } catch (Exception e) {
            log.warn("Episodic Memory 去重召回失败，userId={}, projectKey={}", userId, projectKey, e);
            return List.of();
        }

    }

    private Filter.Expression episodicFilter(Long userId, String projectKey) {
        return new Filter.Expression(
                Filter.ExpressionType.AND,
                new Filter.Expression(
                        Filter.ExpressionType.AND,
                        new Filter.Expression(
                                Filter.ExpressionType.EQ,
                                new Filter.Key("source"),
                                new Filter.Value(AiEpisodicMemoryIndexService.SOURCE_EPISODIC_MEMORY)
                        ),
                        new Filter.Expression(
                                Filter.ExpressionType.EQ,
                                new Filter.Key("userId"),
                                new Filter.Value(userId)
                        )
                ),
                new Filter.Expression(
                        Filter.ExpressionType.EQ,
                        new Filter.Key("projectKey"),
                        new Filter.Value(projectKey)
                )
        );
    }

    private boolean isEpisodicDocument(Document document) {
        return document != null
                && AiEpisodicMemoryIndexService.SOURCE_EPISODIC_MEMORY
                .equals(document.getMetadata().get("source"));
    }

    private EpisodicMemoryRagContext toContext(Document document) {
        Map<String, Object> metadata = document.getMetadata();

        return new EpisodicMemoryRagContext(
                toLong(metadata.get("memoryId")),
                toLong(metadata.get("userId")),
                toStringValue(metadata.get("projectKey")),
                toStringValue(metadata.get("memoryType")),
                toStringValue(metadata.get("title")),
                document.getText(),
                toBigDecimal(metadata.get("confidence")),
                toInteger(metadata.get("importance")),
                toLocalDateTime(metadata.get("occurredAt"))
        );
    }

    private int importanceOrDefault(EpisodicMemoryRagContext context) {
        return context.importance() == null ? 6 : context.importance();
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.valueOf(text);
        }
        return null;
    }

    private Integer toInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.valueOf(text);
        }
        return null;
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal number) {
            return number;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof String text && !text.isBlank()) {
            return new BigDecimal(text);
        }
        return new BigDecimal("0.80");
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime time) {
            return time;
        }
        if (value instanceof String text && !text.isBlank()) {
            return LocalDateTime.parse(text);
        }
        return null;
    }

    private String toStringValue(Object value) {
        return value == null ? "" : value.toString();
    }

}
