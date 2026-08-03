package com.hailin.blogsystem.ai.rag;

import com.hailin.blogsystem.entity.dto.ArticleRagContext;

import java.util.List;
//预留rerank扩展点，当前no-op
public interface ArticleRagRanker {
    List<ArticleRagContext> rank(String question,List<ArticleRagContext> contexts);
}
