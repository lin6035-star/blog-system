package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.Users;
import com.hailin.blogsystem.exception.BusinessException;
import com.hailin.blogsystem.mapper.LoginMapper;
import com.hailin.blogsystem.service.GitHubOAuthService;
import com.hailin.blogsystem.utils.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class GitHubOAuthServiceImpl extends ServiceImpl<LoginMapper, Users>
        implements GitHubOAuthService {

    private final JwtUtil jwtUtil;
    private final RestClient restClient;

    @Value("${github.oauth.client-id}")
    private String clientId;

    @Value("${github.oauth.client-secret}")
    private String clientSecret;

    @Value("${github.oauth.redirect-uri}")
    private String redirectUri;

    public GitHubOAuthServiceImpl(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
        this.restClient = RestClient.builder().build();
    }

    @Override
    public String getAuthorizationUrl() {
        return "https://github.com/login/oauth/authorize"
                + "?client_id=" + clientId
                + "&redirect_uri=" + redirectUri
                + "&scope=read:user";
    }

    @Override
    public String handleCallback(String code) {
        // 1. 用 code 换 GitHub access_token
        String accessToken = exchangeCodeForToken(code);

        // 2. 用 access_token 获取 GitHub 用户信息
        Map<String, Object> githubUser = fetchGitHubUser(accessToken);

        // 3. 查找或创建本地用户
        Users user = findOrCreateUser(githubUser);

        // 4. 生成 JWT
        return jwtUtil.generateToken(user.getId());
    }

    private String exchangeCodeForToken(String code) {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("client_id", clientId);
        formData.add("client_secret", clientSecret);
        formData.add("code", code);
        formData.add("redirect_uri", redirectUri);

        try {
            Map<String, Object> response = restClient.post()
                    .uri("https://github.com/login/oauth/access_token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(formData)
                    .retrieve()
                    .body(Map.class);

            if (response == null || response.get("access_token") == null) {
                String error = response != null ? String.valueOf(response.get("error_description")) : "未知错误";
                throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST,
                        "GitHub 授权失败: " + error);
            }

            return String.valueOf(response.get("access_token"));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(BlogConstants.ErrorCode.SERVER_ERROR,
                    "GitHub 令牌交换失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchGitHubUser(String accessToken) {
        try {
            Map<String, Object> userInfo = restClient.get()
                    .uri("https://api.github.com/user")
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/json")
                    .header("User-Agent", "blog-system")
                    .retrieve()
                    .body(Map.class);

            if (userInfo == null || userInfo.get("id") == null) {
                throw new BusinessException(BlogConstants.ErrorCode.SERVER_ERROR,
                        "获取 GitHub 用户信息失败");
            }

            return userInfo;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(BlogConstants.ErrorCode.SERVER_ERROR,
                    "获取 GitHub 用户信息失败: " + e.getMessage());
        }
    }

    private Users findOrCreateUser(Map<String, Object> githubUser) {
        Long githubId = Long.valueOf(String.valueOf(githubUser.get("id")));
        String login = String.valueOf(githubUser.get("login"));
        String name = githubUser.get("name") != null ? String.valueOf(githubUser.get("name")) : null;
        String avatarUrl = githubUser.get("avatar_url") != null
                ? String.valueOf(githubUser.get("avatar_url")) : "";
        String bio = githubUser.get("bio") != null ? String.valueOf(githubUser.get("bio")) : "";

        // 查找已绑定的 GitHub 账号
        Users user = lambdaQuery()
                .eq(Users::getLoginType, "github")
                .eq(Users::getGithubId, githubId)
                .one();

        if (user != null) {
            // 更新最新信息
            user.setNickname(name != null ? name : login);
            user.setAvatarUrl(avatarUrl);
            user.setBio(bio);
            user.setUpdatedAt(LocalDateTime.now());
            updateById(user);
            return user;
        }

        // 新建 GitHub 账号
        user = new Users();
        user.setUsername(login);
        user.setNickname(name != null ? name : login);
        user.setAvatarUrl(avatarUrl);
        user.setBio(bio);
        user.setGithubId(githubId);
        user.setLoginType("github");
        user.setPasswordHash("");
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        save(user);

        return user;
    }
}
