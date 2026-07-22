package com.hailin.blogsystem.entity.vo;


import com.hailin.blogsystem.entity.Users;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserInfoVO {
    private Long id;
    private String nickname;
    private String avatarUrl;
    private String bio;
    private Long articlesCount;  //发布的公开文章数
    private Long followersCount;  //粉丝数
    private Long followingCount;  //关注者
    private Boolean followed = false;  //表示当前登录用户有没有关注这个主页用户；游客访问时就是 false
    private Boolean self = false;  //表示当前登录用户打开的是不是自己的主页，前端可以用它决定显示“编辑资料”还是“关注”
    private LocalDateTime createdAt;

    public static UserInfoVO from(Users user){
        UserInfoVO userInfoVO = new UserInfoVO();
        userInfoVO.setId(user.getId());
        userInfoVO.setNickname(user.getNickname());
        userInfoVO.setAvatarUrl(user.getAvatarUrl());
        userInfoVO.setBio(user.getBio());
        userInfoVO.setCreatedAt(user.getCreatedAt());

        return userInfoVO;
    }
}
