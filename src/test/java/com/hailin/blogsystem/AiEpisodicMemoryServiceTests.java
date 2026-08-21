package com.hailin.blogsystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.ai.memory.AiEpisodicMemoryIndexService;
import com.hailin.blogsystem.ai.memory.AiEpisodicMemoryRetrieveService;
import com.hailin.blogsystem.entity.dto.EpisodicMemoryRagContext;
import com.hailin.blogsystem.service.impl.AiEpisodicMemoryServiceImpl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiEpisodicMemoryServiceTests {

    private static final String PROJECT_KEY = "global";

    @Test
    void buildsSeparatedEpisodicPrompt() {
        BlogAiProperties properties = new BlogAiProperties();
        properties.setProjectKey(PROJECT_KEY);
        AiEpisodicMemoryIndexService indexService = mock(AiEpisodicMemoryIndexService.class);
        AiEpisodicMemoryRetrieveService retrieveService = mock(AiEpisodicMemoryRetrieveService.class);

        when(retrieveService.retrieveForPrompt(eq(1L), eq(PROJECT_KEY), any()))
                .thenReturn(List.of(new EpisodicMemoryRagContext(
                        10L,
                        1L,
                        PROJECT_KEY,
                        "DECISION",
                        "RAG 选用 ES",
                        "用户和 AI 讨论 RAG 方案后，最终决定使用 ES，因为需要同时支持 Keyword Search 和 Vector Search。",
                        new BigDecimal("0.91"),
                        8,
                        LocalDateTime.of(2026, 8, 17, 21, 30)
                )));

        AiEpisodicMemoryServiceImpl service = new AiEpisodicMemoryServiceImpl(
                properties,
                indexService,
                retrieveService,
                new ObjectMapper()
        );

        String prompt = service.buildEpisodicPrompt(1L, "我们之前为什么决定用 ES？");

        assertThat(prompt).contains("## 历史事件记忆");
        assertThat(prompt).contains("[DECISION]");
        assertThat(prompt).contains("最终决定使用 ES");
        assertThat(prompt).contains("2026-08-17");
    }
}
