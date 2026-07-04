package com.hailin.blogsystem.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("article_tags")
public class ArticleTags {
    private Long articleId;
    private Long tagId;
    private LocalDateTime createdAt;
}
