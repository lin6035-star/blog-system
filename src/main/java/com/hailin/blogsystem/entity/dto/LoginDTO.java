package com.hailin.blogsystem.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 登录请求。
 *
 * <p>边界同 {@link RegisterDTO}：只做「非空 + 对齐列宽上限」，不引入新的下限——
 * 登录尤其不能加下限，否则**历史上用短用户名注册的账号会突然登不进来**。
 */
@Data
public class LoginDTO {

    @NotBlank(message = "用户名不能为空")
    @Size(max = 50, message = "用户名最长 50 个字符")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(max = 64, message = "密码最长 64 个字符")
    private String password;
}
