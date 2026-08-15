package com.hailin.blogsystem;


import com.hailin.blogsystem.ai.rag.ArticleRagIndexService;
import com.hailin.blogsystem.ai.rag.ArticleRagConsistencyService;
import com.hailin.blogsystem.ai.rag.ArticleRagController;
import com.hailin.blogsystem.ai.rag.ArticleRagRetrieveService;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ArticleRagControllerTests {

    private final ArticleRagIndexService articleRagIndexService = mock(ArticleRagIndexService.class);
    private final ArticleRagRetrieveService articleRagRetrieveService = mock(ArticleRagRetrieveService.class);
    private final ArticleRagConsistencyService articleRagConsistencyService = mock(ArticleRagConsistencyService.class);
    private final BlogAiProperties blogAiProperties = mock(BlogAiProperties.class);
    private final ArticleRagController controller = new ArticleRagController(
            articleRagIndexService,
            articleRagRetrieveService,
            articleRagConsistencyService,
            blogAiProperties
    );
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

    @BeforeEach
    void setUp() {
        UserContext.set(1L);

        var rag = mock(BlogAiProperties.Rag.class);
        var rebuild = new BlogAiProperties.Rebuild();
        rebuild.setEnabled(true);
        rebuild.setAllowedUserIds(List.of(1L));
        when(blogAiProperties.getRag()).thenReturn(rag);
        when(rag.getRebuild()).thenReturn(rebuild);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void rebuildsPublishedArticleRagIndex() throws Exception {
        when(articleRagIndexService.indexPublishedArticles()).thenReturn(12);

        mockMvc.perform(post("/api/ai/rag/articles/rebuild"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.indexedChunkCount").value(12));

        verify(articleRagIndexService).indexPublishedArticles();
    }
}
