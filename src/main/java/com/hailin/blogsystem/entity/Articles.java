package com.hailin.blogsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("articles")
public class Articles {

    @TableId( type = IdType.AUTO)
    private Long id;
    private Long categoryId;  //分类ID
    private Long authorId;  //作者用户ID
    private String title;  //文章标题
    private String summary;  //文章摘要
    private String content;  //文章正文，Markdown格式
    private String coverUrl;  //封面图地址
    private Integer status;  //状态：0草稿，1已发布，2隐藏'
    private Integer viewCount;  //浏览数
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic(value = "null", delval = "now()")  // null=未删除，删除时写入当前时间
    private LocalDateTime deletedAt;
    private Integer likeCount;
    private Integer favoriteCount;
    private Integer commentCount;
}
