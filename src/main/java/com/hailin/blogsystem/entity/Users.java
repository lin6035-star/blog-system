package com.hailin.blogsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("users")
public class Users {

    @TableId( type = IdType.ASSIGN_ID)
    private Long id;
    private String username;  //用户名
    private String passwordHash;  //密码哈希
    private String nickname;  //昵称
    private String avatarUrl;  //头像地址
    private String bio;  //个人简介
    private String loginType = "password";  //登录方式: password / github
    private Long githubId;  //GitHub 用户ID
    private Integer status = 1; //状态：0禁用，1正常
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic(value = "null", delval = "now()")  // null=未删除，删除时写入当前时间
    private LocalDateTime deletedAt;

    private Integer followersCount;  //粉丝数
    private Integer followingCount;  //关注数
}
