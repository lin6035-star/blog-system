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
import com.hailin.blogsystem.security.LoginAttemptLimiter;
import com.hailin.blogsystem.security.TokenBlacklist;
import com.hailin.blogsystem.service.LoginService;
import com.hailin.blogsystem.service.WalletService;
import com.hailin.blogsystem.utils.AliyunOSSOperator;
import com.hailin.blogsystem.utils.JwtUtil;
import com.hailin.blogsystem.utils.Result;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class LoginServiceImpl extends ServiceImpl<LoginMapper, Users>
        implements LoginService {

    private final JwtUtil jwtUtil;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final TokenBlacklist tokenBlacklist;
    private final WalletService walletService;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 账号不存在时用来"陪跑"的 bcrypt hash：类加载时现算一个，
     * 不硬编码，也不需要它对应任何真实密码。
     */
    private static final String DUMMY_HASH = new BCryptPasswordEncoder().encode("timing-equalizer");

    @Override
    @Transactional
    public AuthVO register(RegisterDTO registerDTO) {
        validateRegisterDTO(registerDTO);

        Users users = lambdaQuery()
                .eq(Users::getUsername, registerDTO.getUsername())
                .eq(Users::getLoginType, "password")
                .one();
        if(users != null){
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST, "用户名已存在");
        }

        Users user = new Users();
        user.setUsername(registerDTO.getUsername());
        user.setNickname(registerDTO.getNickname());
        user.setPasswordHash(passwordEncoder.encode(registerDTO.getPassword()));
        user.setLoginType("password");
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());

        save(user);
        // 与创建用户同一事务：送礼失败则用户一起回滚，用户能重新注册。
        // 反过来「用户建出来了却报注册失败」会让用户名被自己占死，重试都重试不了。
        walletService.grantInitialCredit(user.getId());

        return buildAuthVO(user);
    }

    @Override
    public AuthVO login(LoginDTO loginDTO, String clientIp) {
        validateLoginDTO(loginDTO);

        // 查库之前先拦：锁定时既不做 bcrypt 计算，也不让"是否被锁"成为账号存在性的探针
        loginAttemptLimiter.checkBlocked(loginDTO.getUsername(), clientIp);

        Users users = lambdaQuery()
                .eq(Users::getUsername, loginDTO.getUsername())
                .eq(Users::getLoginType, "password")
                .one();

        if (users == null) {
            // 账号不存在时也跑一次 bcrypt：否则"立即返回"与"等 ~100ms"的时间差
            // 本身就是账号是否存在的探针，与下面泛化的提示语自相矛盾
            passwordEncoder.matches(loginDTO.getPassword(), DUMMY_HASH);
            throw loginFailed(loginDTO.getUsername(), clientIp);
        }

        if (!passwordEncoder.matches(loginDTO.getPassword(), users.getPasswordHash())) {
            throw loginFailed(loginDTO.getUsername(), clientIp);
        }

        // 只清账号维度：IP 维度表达的是"这个来源可疑"，成功登录证明不了同一出口下的其他人（详见类注释）
        loginAttemptLimiter.clear(loginDTO.getUsername());

        return buildAuthVO(users);
    }

    @Override
    public void logout(String token) {
        if (!StringUtils.hasText(token)) {
            return;
        }

        JwtUtil.Payload payload;
        try {
            payload = jwtUtil.parsePayload(token);
        } catch (Exception e) {
            // token 无效或已过期：它本来就用不了了，不需要（也无法）拉黑
            return;
        }

        // 黑名单只需活到 token 自然过期为止
        long remainingSeconds = payload.expireAtSeconds() - Instant.now().getEpochSecond();
        tokenBlacklist.revoke(payload.jti(), Duration.ofSeconds(remainingSeconds));
    }

    /**
     * 登录失败：计数与提示语绑在一处，保证两个分支（账号不存在 / 密码错误）永远一致——
     * 计数漏一个分支会变成账号存在性的探针，提示语不一致则等于直接告诉攻击者"这个账号有"。
     */
    private BusinessException loginFailed(String username, String clientIp) {
        loginAttemptLimiter.recordFailure(username, clientIp);
        return new BusinessException(BlogConstants.ErrorCode.LOGIN_FAILED, "用户名或密码错误");
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
