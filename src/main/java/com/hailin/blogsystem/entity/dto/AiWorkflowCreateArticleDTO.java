package com.hailin.blogsystem.entity.dto;

import lombok.Data;

@Data
public class AiWorkflowCreateArticleDTO {

    private Long conversationId;  //关联的 AI 会话 ID，可为空
    private String requirement;  //用户原始写作需求，例如：帮我写一篇 Redis 缓存穿透博客
    private PageContextDTO pageContext;  //当前页面上下文，例如编辑器页、文章详情页
}