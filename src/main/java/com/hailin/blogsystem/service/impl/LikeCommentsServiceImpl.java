package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hailin.blogsystem.component.UserSetCache;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.entity.ArticleComments;
import com.hailin.blogsystem.entity.CommentLikes;
import com.hailin.blogsystem.mapper.CommentsMapper;
import com.hailin.blogsystem.mapper.LikeCommentsMapper;
import com.hailin.blogsystem.service.LikeCommentsService;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class LikeCommentsServiceImpl extends ServiceImpl<LikeCommentsMapper, CommentLikes> implements LikeCommentsService {

    private final CommentsMapper commentsMapper;
    private final UserSetCache userSetCache;

    @Override  //1.点赞评论，登录用户可访问
    @Transactional
    public void likeComment(Long commentId) {

        CommentLikes one = lambdaQuery().eq(CommentLikes::getCommentId, commentId)
                .eq(CommentLikes::getUserId, UserContext.get()).one();
        if(one != null){
            throw new IllegalArgumentException("不能重复点赞");
        }

        ArticleComments comment = commentsMapper.selectById(commentId);

        comment.setLikeCount(comment.getLikeCount() + 1);

        commentsMapper.updateById(comment);

        CommentLikes commentLikes = new CommentLikes();
        commentLikes.setCommentId(commentId);
        commentLikes.setUserId(UserContext.get());
        commentLikes.setCreatedAt(LocalDateTime.now());

        save(commentLikes);

        //维护用户评论点赞集合：仅在集合已加载时同步，未加载时什么都不做——
        //保持"未加载"让下次读从 DB 全量重建，否则会留下一个只有这一条记录的集合却自称全量
        Long userId = UserContext.get();
        userSetCache.addIfLoaded(
                RedisConstants.COMMENT_LIKED_USER_KEY_PREFIX + userId, String.valueOf(commentId));
    }


    @Override  //2.取消点赞评论，登录用户可访问
    @Transactional
    public void cancelLike(Long commentId) {

        boolean remove = removeById(lambdaQuery().eq(CommentLikes::getCommentId, commentId)
                .eq(CommentLikes::getUserId, UserContext.get()).one());

        if(remove){
            ArticleComments comment = commentsMapper.selectById(commentId);

            comment.setLikeCount(comment.getLikeCount() - 1);

            commentsMapper.updateById(comment);
        }

        //维护用户评论点赞集合：取消最后一项时状态会转为 EMPTY，不留「全量标记 + 空集合」
        Long userId = UserContext.get();
        userSetCache.removeIfLoaded(
                RedisConstants.COMMENT_LIKED_USER_KEY_PREFIX + userId, String.valueOf(commentId));
    }
}
