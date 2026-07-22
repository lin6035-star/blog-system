package com.hailin.blogsystem.entity.vo;

import com.hailin.blogsystem.entity.Users;
import lombok.Data;

import java.time.LocalDateTime;

@Data  //AuthVO 是认证结果，里面包含 token + user
public class UsersVO {  //UserVO 是用户资料
    private Long id;
    private String username;
    private String nickname;
    private String avatarUrl;
    private String bio;
    private Long followersCount;
    private Long followingCount;
    private LocalDateTime createdAt;

    public static UsersVO from(Users user){
        UsersVO usersVO = new UsersVO();
        usersVO.setId(user.getId());
        usersVO.setUsername(user.getUsername());
        usersVO.setNickname(user.getNickname());
        usersVO.setAvatarUrl(user.getAvatarUrl());
        usersVO.setBio(user.getBio());
        usersVO.setFollowersCount(user.getFollowersCount() == null ? 0L : user.getFollowersCount().longValue());
        usersVO.setFollowingCount(user.getFollowingCount() == null ? 0L : user.getFollowingCount().longValue());
        usersVO.setCreatedAt(user.getCreatedAt());

        return usersVO;
    }
}
