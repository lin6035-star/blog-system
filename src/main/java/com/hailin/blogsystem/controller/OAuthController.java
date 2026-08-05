package com.hailin.blogsystem.controller;

import com.hailin.blogsystem.service.GitHubOAuthService;
import com.hailin.blogsystem.utils.Result;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class OAuthController {

    private final GitHubOAuthService gitHubOAuthService;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    /** 获取 GitHub 授权页 URL，前端拿到来跳转 */
    @GetMapping("/github/url")
    public Result<Map<String, String>> getAuthorizationUrl() {
        String url = gitHubOAuthService.getAuthorizationUrl();
        return Result.success(Map.of("url", url));
    }

    /** GitHub 回调：换 token、拿用户信息、登录/注册、重定向到前端 */
    @GetMapping("/github/callback")
    public void handleCallback(@RequestParam String code,
                               HttpServletResponse response) throws IOException {
        String token = gitHubOAuthService.handleCallback(code);
        response.sendRedirect(frontendUrl + "/auth/github/callback?token=" + token);
    }
}
