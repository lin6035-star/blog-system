package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.rag.ArticleRagPromptBuilder;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArticleRagPromptBuilderTests {

    private final ArticleRagPromptBuilder builder = new ArticleRagPromptBuilder();

    @Test
    void keepsOriginalPromptWhenContextsAreEmpty() {
        String prompt = builder.buildPrompt("用户问题：Redis 是什么？", List.of());

        assertThat(prompt).isEqualTo("用户问题：Redis 是什么？");
    }

    @Test
    void appendsArticleRagContextsToPrompt() {
        String prompt = builder.buildPrompt(
                "用户问题：Redis 能做什么？",
                List.of(new ArticleRagContext(
                        1L,
                        "Redis 实战",
                        0,
                        "Redis 可以用来做缓存、排行榜、点赞状态和浏览量统计。"
                ))
        );

        assertThat(prompt).contains("用户问题：Redis 能做什么？");
        assertThat(prompt).contains("## 站内文章知识库检索结果");
        assertThat(prompt).contains("文章标题：Redis 实战");
        assertThat(prompt).contains("片段内容：");
        assertThat(prompt).contains("Redis 可以用来做缓存");
    }

    @Test
    void includesReferenceNumberInstructionsInPrompt() {
        String prompt = builder.buildPrompt(
                "用户问题：Redis 能做什么？",
                List.of(
                        new ArticleRagContext(
                                1L,
                                "Redis 实战",
                                0,
                                "Redis 可以用来做缓存。"
                        ),
                        new ArticleRagContext(
                                2L,
                                "秒杀系统",
                                0,
                                "Redis 可以用于库存预扣。"
                        )
                )
        );

        assertThat(prompt).contains("来源 [1]");
        assertThat(prompt).contains("来源 [2]");
        assertThat(prompt).contains("回答中的关键结论后面必须使用来源编号");
        assertThat(prompt).contains("不要使用不存在的编号");
    }
}