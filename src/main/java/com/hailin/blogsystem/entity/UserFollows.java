package com.hailin.blogsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_follows")
public class UserFollows {

    @TableId( type = IdType.AUTO)
    private Long id;
    private Long followerId;  //关注者的id
    private Long followingId;  //被关注者id
    private LocalDateTime createdAt;  //首次关注时间
    private LocalDateTime updatedAt;  //更新时间
    private LocalDateTime deletedAt;  //取关时间
}
