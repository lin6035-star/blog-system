package com.hailin.blogsystem.ai.tool;

import com.hailin.blogsystem.entity.AiArticleActionCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

@RequiredArgsConstructor
public class AiArticleActionTools {
    private final String requestId;
    private final AiToolActionRegistry registry;

    @Tool(description = "在文章详情页点赞当前文章。用户说点赞这篇文章，喜欢该文章是调用")
    public String likeArticle(
            @ToolParam(description = "当前文章的ID，来自页面上下文 articleId") String articleId
    ){
        AiArticleActionCommand command = new AiArticleActionCommand();
        command.setType("likeArticle");
        command.setArticleId(articleId);

        registry.setArticleAction(requestId,command);
        return "已准备点赞当前文章";
    }

    @Tool(description = "在文章详情页取消点赞当前文章。用户说取消当前文章时调用")
    public String unlikeArticle(
            @ToolParam(description = "当前文章ID，来自页面上下文articleId") String articleId
    ){
        AiArticleActionCommand command = new AiArticleActionCommand();
        command.setType("unlikeArticle");
        command.setArticleId(articleId);
        registry.setArticleAction(requestId, command);
        return "已准备取消点赞当前文章。";
    }


    @Tool(description = "在文章详情页收藏当前文章。用户说收藏这篇文章、加入收藏时调用。")
    public String favoriteArticle(
            @ToolParam(description = "当前文章ID，来自页面上下文 articleId") String articleId
    ) {
        AiArticleActionCommand command = new AiArticleActionCommand();
        command.setType("favoriteArticle");
        command.setArticleId(articleId);
        registry.setArticleAction(requestId, command);
        return "已准备收藏当前文章。";
    }

    @Tool(description = "在文章详情页取消收藏当前文章。用户说取消收藏这篇文章时调用。")
    public String unfavoriteArticle(
            @ToolParam(description = "当前文章ID，来自页面上下文 articleId") String articleId
    ) {
        AiArticleActionCommand command = new AiArticleActionCommand();
        command.setType("unfavoriteArticle");
        command.setArticleId(articleId);
        registry.setArticleAction(requestId, command);
        return "已准备取消收藏当前文章。";
    }

    @Tool(description = "在文章详情页评论当前文章。用户要求评论该文章，发表看法，帮我评论时调用")
    public String commentArticle(
            @ToolParam(description = "当前文章ID，来自页面上下文 articleId") String articleId,
            @ToolParam(description = "评论内容") String content
    ){
        AiArticleActionCommand command = new AiArticleActionCommand();
        command.setType("commentArticle");
        command.setArticleId(articleId);
        command.setContent(content);

        registry.setArticleAction(requestId,command);

        return "已准备评论当前文章。";
    }

    @Tool(description = "在文章详情页滚动到评论区。用户说查看评论区、跳到评论区、带我去评论区时调用。")
    public String scrollToComments(
            @ToolParam(description = "当前文章ID，来自页面上下文 articleId") String articleId
    ){
        AiArticleActionCommand command = new AiArticleActionCommand();
        command.setType("scrollToComments");
        command.setArticleId(articleId);

        registry.setArticleAction(requestId,command);

        return "已准备跳转到评论区。";
    }


    @Tool(description = "复制或分享当前文章链接。用户说分享这篇文章、复制文章链接时调用。")
    public String copyArticleLink(
            @ToolParam(description = "当前文章ID，来自页面上下文 articleId") String articleId
    ) {
        AiArticleActionCommand command = new AiArticleActionCommand();
        command.setType("copyArticleLink");
        command.setArticleId(articleId);

        registry.setArticleAction(requestId, command);

        return "已准备复制当前文章链接。";
    }


    @Tool(description = "在文章详情页关注当前文章作者。用户说关注作者、关注这个作者时调用。")
    public String followAuthor(
            @ToolParam(description = "当前文章ID，来自页面上下文 articleId") String articleId
    ) {
        AiArticleActionCommand command = new AiArticleActionCommand();
        command.setType("followAuthor");
        command.setArticleId(articleId);

        registry.setArticleAction(requestId, command);

        return "已准备关注当前文章作者。";
    }

    @Tool(description = "在文章详情页取消关注当前文章作者。用户说取消关注作者、不再关注这个作者时调用。")
    public String unfollowAuthor(
            @ToolParam(description = "当前文章ID，来自页面上下文 articleId") String articleId
    ) {
        AiArticleActionCommand command = new AiArticleActionCommand();
        command.setType("unfollowAuthor");
        command.setArticleId(articleId);

        registry.setArticleAction(requestId, command);

        return "已准备取消关注当前文章作者。";
    }
}
