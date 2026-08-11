package com.hailin.blogsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("article_favorites")
public class ArticleFavorites {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long articleId;
    private Long userId;
    private LocalDateTime createTime;
}
