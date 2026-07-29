package com.hailin.blogsystem.entity.dto;

import lombok.Data;

@Data
public class PageContextDTO {
    private String pageType;
    private String path;
    private String articleId;
    private String authorId;
    private String articleTitle;
    private String userId;
}
