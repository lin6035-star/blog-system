package com.hailin.blogsystem.ai.memory;

import com.hailin.blogsystem.entity.dto.MemoryRagContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
//用户问问题时，不靠关键词，而是用当前问题去向量库里召回“语义相关”的记忆。并且必须带 userId 过滤，不能串用户
public class AiMemoryRetrieveService {

    private static final int PROMPT_RETRIEVE_TOP_K = 5;
    private static final int DECISION_TOP_K = 5;
    private static final double SIMILARITY_THRESHOLD = 0.50;

    private final VectorStore vectorStore;

    public List<MemoryRagContext> retrieve(Long userId, String question) {
        if (userId == null || question == null || question.isBlank()) {
            return List.of();
        }

        List<Document> documents = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question.trim())
                        .topK(PROMPT_RETRIEVE_TOP_K)
                        .similarityThreshold(SIMILARITY_THRESHOLD)
                        .filterExpression(memoryFilter(userId))
                        .build()
        );

        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        List<MemoryRagContext> contexts = documents.stream()
                .filter(this::isMemoryDocument)
                .filter(document -> userId.equals(toLong(document.getMetadata().get("userId"))))
                .map(this::toMemoryRagContext)
                .toList();

        log.info("Memory 向量召回完成，userId={}, count={}", userId, contexts.size());

        return contexts;
    }

    public List<MemoryRagContext> retrieveForDecision(Long userId, String memoryType, String content) {
        if (userId == null || memoryType == null || memoryType.isBlank()
                || content == null || content.isBlank()) {
            return List.of();
        }

        try {
            List<Document> documents = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(content.trim())
                            .topK(DECISION_TOP_K)
                            .similarityThreshold(SIMILARITY_THRESHOLD)
                            .filterExpression(memoryTypeFilter(userId, memoryType.trim()))
                            .build()
            );

            if (documents == null || documents.isEmpty()) {
                return List.of();
            }

            List<MemoryRagContext> contexts = documents.stream()
                    .filter(this::isMemoryDocument)
                    .filter(document -> userId.equals(toLong(document.getMetadata().get("userId"))))
                    .filter(document -> memoryType.trim().equals(toStringValue(document.getMetadata().get("memoryType"))))
                    .map(this::toMemoryRagContext)
                    .toList();

            log.info("Memory 更新候选召回完成，userId={}, memoryType={}, count={}",
                    userId, memoryType, contexts.size());

            return contexts;
        } catch (Exception e) {
            log.warn("Memory 更新候选召回失败，userId={}, memoryType={}", userId, memoryType, e);
            return List.of();
        }
    }

    private Filter.Expression memoryFilter(Long userId) {
        return new Filter.Expression(
                Filter.ExpressionType.AND,
                new Filter.Expression(
                        Filter.ExpressionType.EQ,
                        new Filter.Key("source"),
                        new Filter.Value("memory")
                ),
                new Filter.Expression(
                        Filter.ExpressionType.EQ,
                        new Filter.Key("userId"),
                        new Filter.Value(userId)
                )
        );
    }

    private Filter.Expression memoryTypeFilter(Long userId, String memoryType) {
        return new Filter.Expression(
                Filter.ExpressionType.AND,
                memoryFilter(userId),
                new Filter.Expression(
                        Filter.ExpressionType.EQ,
                        new Filter.Key("memoryType"),
                        new Filter.Value(memoryType)
                )
        );
    }

    private boolean isMemoryDocument(Document document) {
        Object source = document.getMetadata().get("source");
        return "memory".equals(source);
    }

    private MemoryRagContext toMemoryRagContext(Document document) {
        Map<String, Object> metadata = document.getMetadata();

        return new MemoryRagContext(
                toLong(metadata.get("memoryId")),
                toLong(metadata.get("userId")),
                toStringValue(metadata.get("memoryType")),
                toStringValue(metadata.get("memoryKey")),
                document.getText(),
                toBigDecimal(metadata.get("confidence")),
                toInteger(metadata.get("importance"))
        );
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
        return BigDecimal.ONE;
    }

    private String toStringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}
