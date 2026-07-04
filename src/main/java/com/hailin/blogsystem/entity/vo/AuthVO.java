package com.hailin.blogsystem.entity.vo;

import lombok.Data;

@Data  //AuthVO 是认证结果，里面包含 token + user
public class AuthVO {  //UserVO 是用户资料
    private String token;
    private UsersVO usersVO;

}
