package com.hailin.blogsystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.mapper.AiMessageMapper;
import com.hailin.blogsystem.service.impl.AiEpisodicMemoryExtractorServiceImpl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void genericAssistantPlanningLanguageDoesNotTriggerExtraction() throws Exception {
        AiMessageMapper messageMapper = mock(AiMessageMapper.class);
        when(messageMapper.selectCount(any())).thenReturn(1L);
        AiEpisodicMemoryExtractorServiceImpl extractor = new AiEpisodicMemoryExtractorServiceImpl(
                messageMapper,
                null,
                new ObjectMapper(),
                null
        );

        extractor.extractAfterChat(
                1L,
                2L,
                3L,
                4L,
                "继续看看这个问题",
                "下一步可以先进入阶段分析，再准备一个方案。"
        );

        verify(messageMapper, never()).selectList(any());
    }

    @Test
    void explicitUserDecisionStillTriggersExtraction() throws Exception {
        AiEpisodicMemoryExtractorServiceImpl extractor = new AiEpisodicMemoryExtractorServiceImpl(
                null,
                null,
                new ObjectMapper(),
                null
        );

        Method method = AiEpisodicMemoryExtractorServiceImpl.class
                .getDeclaredMethod("shouldExtractUserMessage", String.class);
        method.setAccessible(true);

        assertThat((Boolean) method.invoke(extractor, "我们最终决定使用 Elasticsearch。"))
                .isTrue();
        assertThat((Boolean) method.invoke(extractor, "下一步可以继续看看。"))
                .isFalse();
    }
}
