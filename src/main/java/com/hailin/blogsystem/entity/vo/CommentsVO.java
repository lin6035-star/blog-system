package com.hailin.blogsystem.entity.vo;

import com.hailin.blogsystem.entity.ArticleComments;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class CommentsVO {

    // 评论ID，来自 article_comments.id
    private Long id;

    // 所属文章ID，来自 article_comments.article_id
    private Long articleId;

    // 发表评论的用户ID，来自 article_comments.user_id
    private Long userId;

    // 评论者昵称，来自 users.nickname
    private String nickname;

    // 评论者头像，来自 users.avatar_url
    private String avatarUrl;

    // 评论内容，来自 article_comments.content
    private String content;

    // 所属主评论ID；主评论为 null，回复评论指向最顶层主评论
    private Long rootId;

    // 直接回复的评论ID；主评论为 null
    private Long parentId;

    // 被回复用户的昵称，通过 parentId 找到父评论，再用父评论 userId 查询 users.nickname
    private String replyToNickname;

    // 评论者IP属地，来自 article_comments.ip_location，只展示国家或省份
    private String ipLocation;

    // 评论点赞数，来自 article_comments.like_count
    private Long likeCount;

    // 评论创建时间，来自 article_comments.created_at
    private LocalDateTime createdAt;

    // 当前主评论下附带展示的部分回复列表
    private List<CommentsVO> replies;

    // 当前主评论下的回复总数，用于前端显示“展开 N 条回复”
    private Long replyCount;

    // 当前登录用户是否点赞过这条评论，来自 comment_likes 表
    private Boolean liked = false;

    public static CommentsVO from(ArticleComments articleComments){

        if(articleComments == null){
            return null;
        }
        CommentsVO vo = new CommentsVO();
        vo.setId(articleComments.getId());
        vo.setArticleId(articleComments.getArticleId());
        vo.setUserId(articleComments.getUserId());
        vo.setContent(articleComments.getContent());
        vo.setRootId(articleComments.getRootId());
        vo.setParentId(articleComments.getParentId());
        vo.setIpLocation(articleComments.getIpLocation());
        vo.setLikeCount(articleComments.getLikeCount());
        vo.setCreatedAt(articleComments.getCreatedAt());


        return vo;
    }
}
