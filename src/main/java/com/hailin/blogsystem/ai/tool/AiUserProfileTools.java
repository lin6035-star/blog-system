package com.hailin.blogsystem.ai.tool;

import com.hailin.blogsystem.entity.vo.ArticleDetailVO;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.entity.vo.UserInfoVO;
import com.hailin.blogsystem.service.ArticlesService;
import com.hailin.blogsystem.service.UsersService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class AiUserProfileTools {
    private static final Long LIMIT = 3L;

    private final UsersService usersService;
    private final ArticlesService articlesService;

    @Tool(description = "查询个人主页或他人主页的公开用户画像信息。用户询问这个人的主要信息、发过什么文章、点赞收藏评论过什么时调用。")
    public String getUserProfileInsight(
            @ToolParam(description = "当前主页用户ID，来自页面上下文 userId") Long userId
    ){
        if(userId == null){
            return "缺少用户ID，无法查询用户主页信息。";
        }

        UserInfoVO user = usersService.getPublicUserInfo(userId);

        PageVO<ArticleDetailVO> articles =
                articlesService.getPublicUserArticles(userId,1L,LIMIT);

        PageVO<ArticleDetailVO> articleLikes =
                articlesService.getPublicUserLiked(userId,1L,LIMIT);

        PageVO<ArticleDetailVO> articleFavorited =
                articlesService.getPublicUserFavorited(userId,1L,LIMIT);

        PageVO<ArticleDetailVO> commented =
                articlesService.getPublicCommented(userId, 1L, LIMIT);

        StringBuilder sb = new StringBuilder();

        sb.append("用户公开资料：\n");
        sb.append("昵称：").append(user.getNickname()).append("\n");
        sb.append("简介：").append(user.getBio()).append("\n");
        sb.append("加入时间：").append(user.getCreatedAt()).append("\n");
        sb.append("发布文章数：").append(user.getArticlesCount()).append("\n");
        sb.append("关注数：").append(user.getFollowingCount()).append("\n");
        sb.append("粉丝数：").append(user.getFollowersCount()).append("\n\n");

        appendArticleList(sb, "代表文章，例如：", articles.getList(), true);
        appendArticleList(sb, "最近点赞过的文章，例如：", articleLikes.getList(), false);
        appendArticleList(sb, "最近收藏过的文章，例如：", articleFavorited.getList(), false);
        appendArticleList(sb, "最近评论过的文章，例如：", commented.getList(), false);

        sb.append("\n请基于以上公开资料，简洁总结这个用户的主要信息，不要编造列表外的数据。");

        return sb.toString();
    }


    private void appendArticleList(
            StringBuilder sb,
            String title,
            List<ArticleDetailVO> list,
            boolean withStats
    ){
        sb.append(title).append("\n");

        if (list == null || list.isEmpty()) {
            sb.append("暂无\n\n");
            return;
        }

        for (int i = 0; i < list.size(); i++) {
            ArticleDetailVO article = list.get(i);
            sb.append(i + 1).append(". 《").append(article.getTitle()).append("》");

            if (withStats) {
                sb.append("，浏览 ").append(article.getViewCount())
                        .append("，点赞 ").append(article.getLikeCount())
                        .append("，收藏 ").append(article.getFavoriteCount())
                        .append("，评论 ").append(article.getCommentCount());
            }

            sb.append("\n");
        }

        sb.append("\n");

    }
}
