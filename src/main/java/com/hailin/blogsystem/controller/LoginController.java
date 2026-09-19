package com.hailin.blogsystem.controller;

import com.hailin.blogsystem.entity.dto.LoginDTO;
import com.hailin.blogsystem.entity.dto.RegisterDTO;
import com.hailin.blogsystem.entity.vo.AuthVO;
import com.hailin.blogsystem.entity.vo.UsersVO;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.service.LoginService;
import com.hailin.blogsystem.utils.ClientIpUtils;
import com.hailin.blogsystem.utils.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class LoginController {

    private final LoginService loginService;

    @PostMapping("/auth/register") //注册
    public Result<AuthVO> register(@Valid @RequestBody RegisterDTO registerDTO) {

        // 跨字段校验只能留在这里：@Valid 的注解作用在**单个字段**上，
        // 「两次密码是否一致」依赖两个字段的关系，注解表达不了（为它写自定义校验器不值当）。
        // 其余「非空 / 长度」已由 RegisterDTO 上的注解承担，失败时走 GlobalExceptionHandler，
        // 返回的同样是 200 + 40001 —— 对前端完全无感。
        if (!registerDTO.getPassword().equals(registerDTO.getConfirmPassword())) {
            return Result.error(BlogConstants.ErrorCode.BAD_REQUEST, "两次密码不一致！");
        }

        AuthVO authVO = loginService.register(registerDTO);

        return Result.success(authVO);
    }

    @PostMapping("/auth/login")  //登录
    public Result<AuthVO> login(@Valid @RequestBody LoginDTO loginDTO, HttpServletRequest request) {
        AuthVO authVO = loginService.login(loginDTO, ClientIpUtils.getClientIp(request));

        return Result.success(authVO);
    }

    @PostMapping("/auth/logout")  //退出登录
    public Result logout(HttpServletRequest request){
        // /api/auth/** 不在任何拦截器的路径里，token 需要在这里手动取。
        // 幂等：没带 token 或 token 已失效也返回成功——登出要达成的状态本来就是"未登录"
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            loginService.logout(authHeader.substring(7));
        }

        return Result.success();
    }
}
