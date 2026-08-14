package com.hailin.blogsystem.entity.dto;

import lombok.Data;

@Data
public class AiWorkflowOptimizeArticleDTO {

    private Long conversationId;  //关联的 AI 会话 ID，可为空
    private Long articleId;  //待优化的文章 ID
    private String instruction;  //用户对本次优化的具体要求，例如：补充更多 Redis 实战细节
    private PageContextDTO pageContext;  //当前页面上下文，例如编辑器页、文章详情页
}
