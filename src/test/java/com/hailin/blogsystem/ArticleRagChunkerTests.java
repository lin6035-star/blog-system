package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.rag.ArticleRagChunker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArticleRagChunkerTests {

    private final ArticleRagChunker chunker = new ArticleRagChunker();

    @Test
    void returnsEmptyListWhenArticleTextIsBlank() {
        List<String> chunks = chunker.chunk(" ", null, "");

        assertThat(chunks).isEmpty();
    }

    @Test
    void keepsShortArticleInOneChunk() {
        List<String> chunks = chunker.chunk("Java 注解", "注解基础", "正文内容");

        assertThat(chunks)
                .containsExactly("标题：Java 注解\n\n摘要：注解基础\n\n正文：正文内容");
    }

    @Test
    void prefersParagraphBoundaryBeforeHardCuttingText() {
        String firstParagraph = "第一段。" + "甲".repeat(500);
        String secondParagraph = "第二段。" + "乙".repeat(500);

        List<String> chunks = chunker.chunk(
                "长文标题",
                "长文摘要",
                firstParagraph + "\n\n" + secondParagraph
        );

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0))
                .contains("第一段")
                .doesNotContain("第二段");
        assertThat(chunks.get(1)).contains("第二段");
    }

    @Test
    void fallsBackToSplittingLongTextWithoutPunctuation() {
        String content = "无标点内容".repeat(300);

        List<String> chunks = chunker.chunk(null, null, content);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(900));
    }
}
