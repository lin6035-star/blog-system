package com.hailin.blogsystem.entity.dto;

import java.time.LocalDateTime;

public record AiArticleSearchResult(
        Long id,
        String title,
        String summary,
        Integer viewCount,
        String coverUrl,
        LocalDateTime createdAt
) {
}
