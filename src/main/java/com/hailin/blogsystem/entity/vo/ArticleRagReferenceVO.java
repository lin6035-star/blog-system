package com.hailin.blogsystem.entity.vo;

import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import lombok.Data;

@Data
public class ArticleRagReferenceVO {

    private Long articleId;
    private String title;
    private Integer chunkIndex;
    private String snippet;

    public static ArticleRagReferenceVO from(ArticleRagContext context){
        ArticleRagReferenceVO vo = new ArticleRagReferenceVO();
        vo.setArticleId(context.articleId());
        vo.setTitle(context.title());
        vo.setChunkIndex(context.chunkIndex());
        vo.setSnippet(buildSnippet(context.content()));
        return vo;
    }

    private static String buildSnippet(String content){
        if(content == null || content.isBlank()){
            return "";
        }

        String text = content.replaceAll("\\s+"," ").trim();
        if(text.length() <= 50){
            return text;
        }

        return text.substring(0,50) + "...";
    }
}
