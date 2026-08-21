package com.hailin.blogsystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.service.impl.AiEpisodicMemoryExtractorServiceImpl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class AiEpisodicMemoryExtractorTests {

    @Test
    void extractsJsonArrayWithNestedSourceMessageIds() throws Exception {
        AiEpisodicMemoryExtractorServiceImpl extractor = new AiEpisodicMemoryExtractorServiceImpl(
                null,
                null,
                new ObjectMapper(),
                null
        );

        Method method = AiEpisodicMemoryExtractorServiceImpl.class
                .getDeclaredMethod("extractJsonArray", String.class);
        method.setAccessible(true);

        String response = """
                ```json
                [
                  {
                    "shouldRemember": true,
                    "memoryType": "DECISION",
                    "title": "RAG 选用 ES",
                    "content": "用户决定 RAG 使用 ES，因为需要 Keyword + Vector 混合检索。",
                    "importance": 8,
                    "confidence": 0.91,
                    "sourceMessageIds": [123, 124],
                    "occurredAt": "2026-08-17T21:30:00"
                  }
                ]
                """;

        String json = (String) method.invoke(extractor, response);

        assertThat(json).contains("\"sourceMessageIds\": [123, 124]");
        assertThat(json.trim()).startsWith("[");
        assertThat(json.trim()).endsWith("]");
    }
}