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
    AuthVO login(LoginDTO loginDTO);

    UsersVO uploadAvatar(MultipartFile file) throws Exception;
}
