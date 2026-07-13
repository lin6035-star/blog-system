package com.hailin.blogsystem.controller;

import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.service.CommentsService;
import com.hailin.blogsystem.service.LikeCommentsService;
import com.hailin.blogsystem.utils.Result;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/comments/{commentId}/like")
@RequiredArgsConstructor
public class likeCommentsController {

    private final LikeCommentsService likeCommentsService;
    private final CommentsService commentsService;

    @PostMapping("")  //1.点赞评论，登录用户可访问
    public Result likeComment(@PathVariable Long commentId){
        Long userId = UserContext.get();
        if(userId == null){
            return Result.error(BlogConstants.ErrorCode.LOGIN_FAILED,"请先登录");
        }

        likeCommentsService.likeComment(commentId);

        return Result.success();
    }

    @DeleteMapping("")  //2.取消点赞评论，登录用户可访问
    public Result cancelLike(@PathVariable Long commentId){
        likeCommentsService.cancelLike(commentId);

        return Result.success();
    }
}
