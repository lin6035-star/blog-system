package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.Users;
import com.hailin.blogsystem.entity.dto.LoginDTO;
import com.hailin.blogsystem.entity.dto.RegisterDTO;
import com.hailin.blogsystem.entity.vo.AuthVO;
import com.hailin.blogsystem.entity.vo.UsersVO;
import com.hailin.blogsystem.exception.BusinessException;
import com.hailin.blogsystem.mapper.LoginMapper;
import com.hailin.blogsystem.service.LoginService;
import com.hailin.blogsystem.utils.AliyunOSSOperator;
import com.hailin.blogsystem.utils.JwtUtil;
import com.hailin.blogsystem.utils.Result;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class LoginServiceImpl extends ServiceImpl<LoginMapper, Users>
        implements LoginService {

    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public AuthVO register(RegisterDTO registerDTO) {
        validateRegisterDTO(registerDTO);

        Users users = lambdaQuery()
                .eq(Users::getUsername, registerDTO.getUsername())
                .one();
        if(users != null){
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST, "用户名已存在");
        }

        Users user = new Users();
        user.setUsername(registerDTO.getUsername());
        user.setNickname(registerDTO.getNickname());
        user.setPasswordHash(passwordEncoder.encode(registerDTO.getPassword()));
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());

        save(user);

        return buildAuthVO(user);
    }

    @Override
    public AuthVO login(LoginDTO loginDTO) {
        validateLoginDTO(loginDTO);

        Users users = lambdaQuery()
                .eq(Users::getUsername, loginDTO.getUsername())
                .one();

        if(users == null || !passwordEncoder.matches(loginDTO.getPassword(),users.getPasswordHash())){
            throw new BusinessException(BlogConstants.ErrorCode.LOGIN_FAILED, "用户名或密码错误");
        }

        return buildAuthVO(users);
    }

    private final AliyunOSSOperator aliyunOSSOperator;

    @Override  //文件上传OSS
    public UsersVO uploadAvatar(MultipartFile file) throws Exception {
        Long userId = UserContext.get();

        if(userId == null){
            throw new IllegalArgumentException("请先登录");
        }

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择头像文件");
        }

        String contentType = file.getContentType();
        if(contentType == null || !contentType.startsWith("image/")){
            throw new IllegalArgumentException("只能上传图片文件");
        }

        long maxSize = 10*1024*1024;
        if(file.getSize() > maxSize){
            throw new IllegalArgumentException("头像不能超过 10MB,太大了啊！");
        }

        Users user = getById(userId);
        if(user == null){
            throw new IllegalArgumentException("用户不存在");
        }

        String avatarUrl = aliyunOSSOperator.uploadAvatar(
                userId,
                file.getBytes(),
                file.getOriginalFilename()
        );

        user.setAvatarUrl(avatarUrl);
        user.setUpdatedAt(LocalDateTime.now());

        updateById(user);

        return UsersVO.from(user);
    }


    @Override
    public String uploadArticleImage(MultipartFile file) throws Exception {

        Long userId = UserContext.get();

        if (userId == null) {
            throw new BusinessException(BlogConstants.ErrorCode.UNAUTHORIZED,"请先登录");
        }

        if (file == null || file.isEmpty()) {
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST,"请选择图片文件");
        }

        //只允许图片上传
        String contentType = file.getContentType();
        if(contentType == null || !contentType.startsWith("image/")){
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST,"只能上传图片文件");
        }

        //大小限制
        if(file.getSize() > 10*1024*1024){
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST,"图片不能超过 10MB");
        }

        String url = aliyunOSSOperator.uploadArticleImage(
                userId,
                file.getBytes(),
                file.getOriginalFilename()
        );
        return url;
    }


    private AuthVO buildAuthVO(Users user) {
        AuthVO authVO = new AuthVO();
        authVO.setToken(jwtUtil.generateToken(user.getId()));
        authVO.setUsersVO(UsersVO.from(user));
        return authVO;

    }

    private void validateRegisterDTO(RegisterDTO registerDTO) {
        if (registerDTO == null) {
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST, "请求参数不能为空");
        }
        if (!StringUtils.hasText(registerDTO.getUsername())) {
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST, "用户名不能为空");
        }
        if (!StringUtils.hasText(registerDTO.getNickname())) {
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST, "昵称不能为空");
        }
        if (!StringUtils.hasText(registerDTO.getPassword())) {
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST, "密码不能为空!!!");
        }
        if (!StringUtils.hasText(registerDTO.getConfirmPassword())) {
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST, "确认密码不能为空");
        }
        if (!registerDTO.getPassword().equals(registerDTO.getConfirmPassword())) {
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST, "两次密码不一致！");
        }
    }

    private void validateLoginDTO(LoginDTO loginDTO) {
        if (loginDTO == null) {
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST, "请求参数不能为空");
        }
        if (!StringUtils.hasText(loginDTO.getUsername())) {
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST, "用户名不能为空");
        }
        if (!StringUtils.hasText(loginDTO.getPassword())) {
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST, "密码不能为空");
        }
    }
}
