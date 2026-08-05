package com.hailin.blogsystem.service;

public interface GitHubOAuthService {
    /** 获取 GitHub 授权页 URL */
    String getAuthorizationUrl();

    /** 处理 GitHub 回调，返回 JWT token */
    String handleCallback(String code);
}
