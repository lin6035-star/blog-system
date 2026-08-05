package com.hailin.blogsystem.entity.vo;

import com.hailin.blogsystem.entity.Users;
import lombok.Data;

@Data
public class UserRelationVO {
    private Long id;
    private String nickname;
    private String avatarUrl;
    private String bio;
    private Boolean followed = false;
    private Boolean self = false;

    public static UserRelationVO from(Users user) {
        UserRelationVO vo = new UserRelationVO();
        vo.setId(user.getId());
        vo.setNickname(user.getNickname());
        vo.setAvatarUrl(user.getAvatarUrl());
        vo.setBio(user.getBio());
        return vo;
    }
}
