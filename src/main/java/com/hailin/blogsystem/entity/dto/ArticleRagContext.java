package com.hailin.blogsystem.entity.dto;

public record ArticleRagContext (
        Long articleId,  //来自那个文章
        String title,  //文章标题
        Integer chunkIndex,  //来自第几个片段
        String content  //片段正文（被切过的）
){
}
