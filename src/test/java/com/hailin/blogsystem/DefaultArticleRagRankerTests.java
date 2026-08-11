package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.rag.DefaultArticleRagRanker;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultArticleRagRankerTests {

    private final DefaultArticleRagRanker ranker = new DefaultArticleRagRanker();

    @Test
    void keepsOriginalOrderForNow() {
        List<ArticleRagContext> contexts = List.of(
                new ArticleRagContext(1L, "A", 0, "content A"),
                new ArticleRagContext(2L, "B", 0, "content B")
        );

        List<ArticleRagContext> ranked = ranker.rank("Redis", contexts);

        assertThat(ranked).containsExactlyElementsOf(contexts);
    }

    @Test
    void returnsEmptyListWhenContextsAreEmpty() {
        assertThat(ranker.rank("Redis", List.of())).isEmpty();
        assertThat(ranker.rank("Redis", null)).isEmpty();
    }

    @Test
    void filtersUnrelatedContextsForSpecificArticleSearchQuestion() {
        List<ArticleRagContext> contexts = List.of(
                new ArticleRagContext(
                        22L,
                        "Vibe Coding 入门指南：让编程随感觉流动",
                        0,
                        "Vibe Coding 是跟随直觉、情绪和当下灵感写代码，强调编程时的氛围和心流状态。"
                ),
                new ArticleRagContext(
                        16L,
                        "Welcome",
                        0,
                        "这里可以浏览文章列表，也可以分享技术探索、项目实战和生活随笔。"
                ),
                new ArticleRagContext(
                        17L,
                        "Java中的@Builder注解详解",
                        0,
                        "在日常 Java 开发中，我们经常需要创建各种对象。"
                ),
                new ArticleRagContext(
                        18L,
                        "Linux：从命令行到系统思维",
                        0,
                        "Linux 是一个操作系统，也是一种系统思维。"
                )
        );

        List<ArticleRagContext> ranked = ranker.rank("有没有关于氛围编程的文章", contexts);

        assertThat(ranked)
                .extracting(ArticleRagContext::articleId)
                .containsExactly(22L);
    }
}
