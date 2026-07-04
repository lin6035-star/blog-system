package com.hailin.blogsystem.entity.vo;

import com.hailin.blogsystem.entity.Articles;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ArticleDetailVO {

    private Long id;
    private Long categoryId;
    private Long authorId;
    private String title;
    private String summary;
    private String content;
    private String coverUrl;
    private Integer status;
    private Integer viewCount;
    private LocalDateTime publishedAt;

    public static ArticleDetailVO from(Articles article) {
        if (article == null) {
            return null;
        }

        ArticleDetailVO vo = new ArticleDetailVO();
        vo.setId(article.getId());
        vo.setCategoryId(article.getCategoryId());
        vo.setAuthorId(article.getAuthorId());
        vo.setTitle(article.getTitle());
        vo.setSummary(article.getSummary());
        vo.setContent(article.getContent());
        vo.setCoverUrl(article.getCoverUrl());
        vo.setStatus(article.getStatus());
        vo.setViewCount(article.getViewCount());
        vo.setPublishedAt(article.getPublishedAt());
        return vo;
    }
}
