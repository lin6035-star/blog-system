package com.hailin.blogsystem.ai.memory;

import com.hailin.blogsystem.entity.AiEpisodicMemories;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AiEpisodicMemoryIndexService {

    public static final String SOURCE_EPISODIC_MEMORY = "episodic_memory";

    private final VectorStore vectorStore;

    public void indexMemory(AiEpisodicMemories memory){
        if (memory == null || memory.getId() == null || memory.getUserId() == null) {
            return;
        }

        deleteMemoryIndex(memory.getId());

        Document document = Document.builder()
                .id(buildDocumentId(memory.getId()))
                .text(memory.getContent())
                .metadata("source", SOURCE_EPISODIC_MEMORY)
                .metadata("memoryId", memory.getId())
                .metadata("userId", memory.getUserId())
                .metadata("sessionId", memory.getSessionId())
                .metadata("projectKey", memory.getProjectKey())
                .metadata("memoryType", memory.getMemoryType())
                .metadata("title", memory.getTitle())
                .metadata("importance", memory.getImportance())
                .metadata("confidence", memory.getConfidence())
                .metadata("contentHash", memory.getContentHash())
                .metadata("sourceMessageIds", memory.getSourceMessageIds())
                .metadata("occurredAt", memory.getOccurredAt() == null ? null : memory.getOccurredAt().toString())
                .build();

        vectorStore.add(List.of(document));
    }

    public void deleteMemoryIndex(Long memoryId){
        if (memoryId == null) {
            return;
        }

        vectorStore.delete(new Filter.Expression(
                Filter.ExpressionType.AND,
                new Filter.Expression(
                        Filter.ExpressionType.EQ,
                        new Filter.Key("source"),
                        new Filter.Value(SOURCE_EPISODIC_MEMORY)
                ),
                new Filter.Expression(
                        Filter.ExpressionType.EQ,
                        new Filter.Key("memoryId"),
                        new Filter.Value(memoryId)
                )
        ));
    }

    private String buildDocumentId(Long memoryId) {
        return "episodic:" + memoryId;
    }

}
