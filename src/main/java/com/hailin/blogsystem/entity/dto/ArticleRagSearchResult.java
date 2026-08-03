package com.hailin.blogsystem.entity.dto;

import java.util.List;

public record ArticleRagSearchResult(
        ArticleRagSearchStrategy strategy,
        List<ArticleRagContext> contexts
) {
}
