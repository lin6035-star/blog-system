package com.hailin.blogsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("comment_likes")
public class CommentLikes {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long commentId;  //'评论ID'
    private Long userId;  //'点赞用户ID'
    private LocalDateTime createdAt;
}
