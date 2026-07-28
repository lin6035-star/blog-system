package com.hailin.blogsystem.ai.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

@Slf4j
@RequiredArgsConstructor
public class AiNavigationTools {

    private final String requestId;
    private final AiToolActionRegistry registry;

    @Tool(description = "跳转到首页/文章列表页")
    public String goToHome() {
        return navigate("home", null, "已准备跳转到首页。");
    }

    @Tool(description = "跳转到写文章/编辑器页面")
    public String goToEditor() {
        return navigate("editor", null, "已准备打开写文章页面。");
    }

    @Tool(description = "跳转到指定文章的详情页")
    public String goToArticle(@ToolParam(description = "文章ID") Long articleId) {
        return navigate("article", String.valueOf(articleId), "已准备跳转到文章详情页。");
    }


    @Tool(description = "跳转到个人中心页面")
    public String goToProfile() {
        return navigate("profile", null, "已准备跳转到个人中心。");
    }


    @Tool(description = "跳转到草稿箱页面")
    public String goToDrafts() {
        return navigate("drafts", null, "已准备跳转到草稿箱。");
    }

    @Tool(description = "跳转到热门排行榜页面")
    public String goToHotRank() {
        return navigate("hotRank", null, "已准备跳转到热门排行。");
    }

    @Tool(description = "跳转到指定用户的公开主页")
    public String goToUserProfile(
            @ToolParam(description = "用户ID") Long userId
    ) {
        return navigate("userProfile", String.valueOf(userId), "已准备跳转到该用户的主页。");
    }

    private String navigate(String target, String param, String message) {
        log.info("AI导航工具调用: requestId={}, target={}, param={}", requestId, target, param);
        registry.setNavigate(requestId, target, param);
        return message;
    }
}
