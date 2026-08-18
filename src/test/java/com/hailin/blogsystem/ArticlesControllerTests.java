package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.rag.ArticleRagIndexService;
import com.hailin.blogsystem.ai.rag.ArticleRagSyncService;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.service.ArticlesService;
import com.hailin.blogsystem.utils.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ArticlesControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ArticlesService articlesService;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private ArticleRagSyncService articleRagSyncService;

    @BeforeEach
    void resetArticleState() {
        Articles article1 = articlesService.getById(1L);
        article1.setViewCount(12);

        Articles article3 = articlesService.getById(3L);
        article3.setViewCount(100);

        Articles article4 = articlesService.getById(4L);
        article4.setViewCount(1);

        articlesService.updateBatchById(List.of(article1, article3, article4));
        cleanRedisKeys();
    }

    private void cleanRedisKeys() {
        try {
            deleteByPattern(RedisConstants.ARTICLE_LIST_KEY_PREFIX + "*");
            deleteByPattern(RedisConstants.ARTICLE_DETAIL_KEY_PREFIX + "*");
            deleteByPattern(RedisConstants.ARTICLE_VIEW_KEY_PREFIX + "*");
            stringRedisTemplate.delete(RedisConstants.ARTICLE_HOT_KEY);
            stringRedisTemplate.delete(RedisConstants.CATEGORY_LIST_KEY);
        } catch (Exception ignored) {
            // Redis 不可用时，业务会走 DB 兜底；测试清理也不阻断。
        }
    }

    private void deleteByPattern(String pattern) {
        Set<String> keys = stringRedisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    @Test
    void getsPublishedArticlesPage() throws Exception {
        mockMvc.perform(get("/api/articles")
                        .param("page", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list", hasSize(3)))
                .andExpect(jsonPath("$.data.list[0].id").value(4))
                .andExpect(jsonPath("$.data.list[0].authorName").value("Author Nick"))
                .andExpect(jsonPath("$.data.list[0].categoryName").value("AI / Agent"))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(10));
    }

    @Test
    void createsPublishedArticleWithPublishedAt() throws Exception {
        String authorToken = jwtUtil.generateToken(100L);

        try {
            mockMvc.perform(post("/api/users/me/articles")
                            .header("Authorization", "Bearer " + authorToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "categoryId": 1,
                                      "title": "New Published Article",
                                      "summary": "New summary",
                                      "content": "New content",
                                      "coverUrl": "",
                                      "status": 1
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data").isNumber());

            Articles article = articlesService.lambdaQuery()
                    .eq(Articles::getTitle, "New Published Article")
                    .one();

            org.assertj.core.api.Assertions.assertThat(article.getStatus()).isEqualTo(1);
            org.assertj.core.api.Assertions.assertThat(article.getPublishedAt()).isNotNull();
        } finally {
            articlesService.lambdaUpdate()
                    .eq(Articles::getTitle, "New Published Article")
                    .remove();
        }
    }

    @Test
    void filtersPublishedArticlesByCategoryId() throws Exception {
        mockMvc.perform(get("/api/articles")
                        .param("page", "1")
                        .param("pageSize", "10")
                        .param("categoryId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list", hasSize(3)))
                .andExpect(jsonPath("$.data.total").value(3));

        mockMvc.perform(get("/api/articles")
                        .param("page", "1")
                        .param("pageSize", "10")
                        .param("categoryId", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list", hasSize(0)))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void sortsPublishedArticlesWithinCategory() throws Exception {
        mockMvc.perform(get("/api/articles")
                        .param("page", "1")
                        .param("pageSize", "10")
                        .param("categoryId", "1")
                        .param("sort", "recommend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list[0].id").value(3))
                .andExpect(jsonPath("$.data.list[0].viewCount").value(100));

        mockMvc.perform(get("/api/articles")
                        .param("page", "1")
                        .param("pageSize", "10")
                        .param("categoryId", "1")
                        .param("sort", "latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list[0].id").value(4))
                .andExpect(jsonPath("$.data.list[0].publishedAt").value("2026-07-03T10:00:00"));
    }

    @Test
    void getsPublishedArticleDetail() throws Exception {
        mockMvc.perform(get("/api/articles/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.title").value("Published Article"))
                .andExpect(jsonPath("$.data.authorName").value("Author Nick"))
                .andExpect(jsonPath("$.data.categoryName").value("AI / Agent"))
                .andExpect(jsonPath("$.data.content").value("Published content"));
    }

    @Test
    void doesNotIncreaseViewCountForAnonymousVisitor() throws Exception {
        mockMvc.perform(get("/api/articles/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        Articles article = articlesService.getById(1L);
        org.assertj.core.api.Assertions.assertThat(article.getViewCount()).isEqualTo(12);
    }

    @Test
    void doesNotIncreaseViewCountForAuthor() throws Exception {
        String authorToken = jwtUtil.generateToken(100L);

        mockMvc.perform(get("/api/articles/1")
                        .header("Authorization", "Bearer " + authorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        Articles article = articlesService.getById(1L);
        org.assertj.core.api.Assertions.assertThat(article.getViewCount()).isEqualTo(12);
    }

    @Test
    void increasesViewCountForLoggedInNonAuthor() throws Exception {
        String readerToken = jwtUtil.generateToken(101L);

        mockMvc.perform(get("/api/articles/1")
                        .header("Authorization", "Bearer " + readerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.viewCount").value(13));
    }

    @Test
    void rejectsDraftArticleDetail() throws Exception {
        mockMvc.perform(get("/api/articles/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40400))
                .andExpect(jsonPath("$.message").value("article not found"));
    }

    @Test
    void getsCategoriesInSortOrder() throws Exception {
        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data", hasSize(4)))
                .andExpect(jsonPath("$.data[0].code").value("ai-agent"))
                .andExpect(jsonPath("$.data[1].code").value("java-backend"))
                .andExpect(jsonPath("$.data[2].code").value("project-practice"))
                .andExpect(jsonPath("$.data[3].code").value("notes"));
    }
}
