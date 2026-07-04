package com.hailin.blogsystem.controller;

import com.hailin.blogsystem.entity.dto.LoginDTO;
import com.hailin.blogsystem.entity.dto.RegisterDTO;
import com.hailin.blogsystem.entity.vo.AuthVO;
import com.hailin.blogsystem.entity.vo.UsersVO;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.service.LoginService;
import com.hailin.blogsystem.utils.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class LoginController {

    private final LoginService loginService;

    @PostMapping("/auth/register") //注册
    public Result<AuthVO> register(@RequestBody RegisterDTO registerDTO){

        if (registerDTO == null) {
            return Result.error(BlogConstants.ErrorCode.BAD_REQUEST, "请求参数不能为空");
        }

        if (!StringUtils.hasText(registerDTO.getUsername())) {
            return Result.error(BlogConstants.ErrorCode.BAD_REQUEST, "用户名不能为空");
        }

        if (!StringUtils.hasText(registerDTO.getNickname())) {
            return Result.error(BlogConstants.ErrorCode.BAD_REQUEST, "昵称不能为空");
        }

        if(!StringUtils.hasText(registerDTO.getPassword())){
            return Result.error(BlogConstants.ErrorCode.BAD_REQUEST, "密码不能为空!!!");
        }

        if (!StringUtils.hasText(registerDTO.getConfirmPassword())) {
            return Result.error(BlogConstants.ErrorCode.BAD_REQUEST, "确认密码不能为空");
        }

        if(!registerDTO.getPassword().equals(registerDTO.getConfirmPassword())){
            return Result.error(BlogConstants.ErrorCode.BAD_REQUEST, "两次密码不一致！");
        }

        AuthVO authVO = loginService.register(registerDTO);

        return Result.success(authVO);
    }

    @PostMapping("/auth/login")  //登录
    public Result<AuthVO> login(@RequestBody LoginDTO loginDTO){
        if (loginDTO == null) {
            return Result.error(BlogConstants.ErrorCode.BAD_REQUEST, "请求参数不能为空");
        }

        if (!StringUtils.hasText(loginDTO.getUsername())) {
            return Result.error(BlogConstants.ErrorCode.BAD_REQUEST, "用户名不能为空");
        }

        if (!StringUtils.hasText(loginDTO.getPassword())) {
            return Result.error(BlogConstants.ErrorCode.BAD_REQUEST, "密码不能为空");
        }

        AuthVO authVO = loginService.login(loginDTO);

        return Result.success(authVO);
    }

    @PostMapping("/auth/logout")  //退出登录
    public Result logout(){
        return Result.success();
    }
}
