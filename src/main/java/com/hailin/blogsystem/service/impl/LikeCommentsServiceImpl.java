package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
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

    }
}
