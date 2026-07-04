package com.hailin.blogsystem.controller;

import com.hailin.blogsystem.entity.vo.UsersVO;
import com.hailin.blogsystem.service.LoginService;
import com.hailin.blogsystem.utils.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RequiredArgsConstructor
@RequestMapping("/api/users")
@RestController
public class UploadController {

    private final LoginService loginService;

    @PostMapping("/me/avatar")
    public Result<UsersVO> uploadAvatar(@RequestParam("file")MultipartFile file) throws Exception{
        UsersVO usersVO = loginService.uploadAvatar(file);

        return Result.success(usersVO);
    }
}
