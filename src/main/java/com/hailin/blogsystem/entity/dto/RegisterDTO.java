package com.hailin.blogsystem.entity.dto;

import lombok.Data;

@Data
public class RegisterDTO {

    private String username;
    private String password;
    private String confirmPassword;
    private String nickname;
}
