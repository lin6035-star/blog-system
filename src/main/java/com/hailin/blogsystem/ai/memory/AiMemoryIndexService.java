package com.hailin.blogsystem.ai.memory;

import com.hailin.blogsystem.entity.AiUserMemories;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
//MySQL 还是正式记忆主库，但每条正式记忆会同步写一份到 VectorStore，用来做语义召回
public class AiMemoryIndexService {

    private final VectorStore vectorStore;

    public void indexMemory(AiUserMemories memory){
        if (memory == null || memory.getId() == null || memory.getUserId() == null) {
            return;
        }

        deleteMemoryIndex(memory.getId());

        Document document = Document.builder()
                .id(buildDocumentId(memory.getId()))
                .text(memory.getContent())
                .metadata("source", "memory")
                .metadata("memoryId", memory.getId())
                .metadata("userId", memory.getUserId())
                .metadata("memoryType", memory.getMemoryType())
                .metadata("memoryKey", memory.getMemoryKey())
                .metadata("confidence", memory.getConfidence())
                .metadata("importance", memory.getImportance())
                .build();

        vectorStore.add(List.of(document));
    }
    public void deleteMemoryIndex(Long memoryId) {
        if (memoryId == null) {
            return;
        }

        vectorStore.delete(new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key("memoryId"),
                new Filter.Value(memoryId)
        ));
    }
    private String buildDocumentId(Long memoryId) {
        return "memory:" + memoryId;
    }
}
