package com.hailin.blogsystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hailin.blogsystem.entity.Users;
import com.hailin.blogsystem.entity.dto.LoginDTO;
import com.hailin.blogsystem.entity.dto.RegisterDTO;
import com.hailin.blogsystem.entity.vo.AuthVO;
import com.hailin.blogsystem.entity.vo.UsersVO;
import org.springframework.web.multipart.MultipartFile;

public interface LoginService extends IService<Users> {
    AuthVO register(RegisterDTO registerDTO);

    /** @param clientIp 登录失败限制的 **IP 维度**取值（账号维度只看用户名），见 LoginAttemptLimiter */
    AuthVO login(LoginDTO loginDTO, String clientIp);

    /** 登出：把该 token 拉黑。token 无效或缺失时静默返回（登出是幂等的） */
    void logout(String token);

    UsersVO uploadAvatar(MultipartFile file) throws Exception;

    String uploadArticleImage(MultipartFile file) throws Exception;
}
