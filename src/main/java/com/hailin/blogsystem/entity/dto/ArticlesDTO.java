package com.hailin.blogsystem.entity.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ArticlesDTO {

    private Long categoryId;  //分类ID
    private String title;  //文章标题
    private String summary;  //文章摘要
    private String content;  //文章正文，Markdown格式
    private String coverUrl;  //封面图地址
    private Integer status;  //状态：0草稿，1已发布，2隐藏'
    private LocalDateTime publishedAt;

}
