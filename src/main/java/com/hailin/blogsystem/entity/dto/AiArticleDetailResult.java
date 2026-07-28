package com.hailin.blogsystem.entity.dto;

import java.time.LocalDateTime;

public record AiArticleDetailResult(
        Long id,
        String title,
        String summary,
        String content,
        Integer viewCount,
        LocalDateTime createdAt
) {
}
