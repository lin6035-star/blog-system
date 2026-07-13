package com.hailin.blogsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hailin.blogsystem.entity.vo.CommentsVO;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("article_comments")
public class ArticleComments {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long articleId;
    private Long userId;
    private String content;
    private Long rootId;
    private Long parentId;
    private String ip;
    private String ipLocation;
    private Long likeCount = 0L;
    private LocalDateTime createdAt;
    @TableLogic(value = "null", delval = "now()")  // null=未删除，删除时写入当前时间
    private LocalDateTime deletedAt;
    private Long deletedBy;

}
