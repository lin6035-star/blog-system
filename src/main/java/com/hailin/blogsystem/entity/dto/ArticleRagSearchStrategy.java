package com.hailin.blogsystem.entity.dto;

public enum ArticleRagSearchStrategy {
    VECTOR,          // 纯 ES 向量检索（普通 RAG）
    HYBRID,          // ES 向量 + keyword 混合检索（ARTICLE_SEARCH）
    MYSQL_FALLBACK,  // MySQL LIKE 兜底
    EMPTY
}