package com.hailin.blogsystem.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求。
 *
 * <p><b>校验边界对齐数据库列宽</b>（username / nickname 都是 varchar(50)）：
 * 原先只在 controller 里校验「非空」，超长会一路写到库里才炸——那时返回的是 500，
 * 用户看到的是「服务器错误」而不是「你输入太长了」。
 *
 * <p><b>刻意不加「最少几位」这类下限</b>：那是**新增约束**，会拒绝掉原本能注册的输入。
 * 参数校验的目的是把「会在更深处炸掉的问题拦在前面」，不是借机收紧产品规则——
 * 真要加长度下限，应该是独立的一次产品决策，而不是顺手夹在工程化改造里。
 */
@Data
public class RegisterDTO {

    @NotBlank(message = "用户名不能为空")
    @Size(max = 50, message = "用户名最长 50 个字符")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(max = 64, message = "密码最长 64 个字符")
    private String password;

    @NotBlank(message = "确认密码不能为空")
    private String confirmPassword;

    @NotBlank(message = "昵称不能为空")
    @Size(max = 50, message = "昵称最长 50 个字符")
    private String nickname;
}
