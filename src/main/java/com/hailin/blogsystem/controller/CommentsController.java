package com.hailin.blogsystem.controller;

import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.dto.CommentDTO;
import com.hailin.blogsystem.entity.vo.CommentsVO;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.service.CommentsService;
import com.hailin.blogsystem.utils.Result;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CommentsController {

    private final CommentsService commentsService;

    @GetMapping("/articles/{articleId}/comments")  //1.获取文章评论列表，游客可访问，每条主评论带前几条回复
    public Result<PageVO<CommentsVO>> getComments(@PathVariable Long articleId, @RequestParam(defaultValue = "1") Long page,
                                                  @RequestParam(defaultValue = "20") Long pageSize, @RequestParam(defaultValue = "time") String sort){
        PageVO<CommentsVO> commentsList = commentsService.getComments(articleId,page,pageSize,sort);

        return Result.success(commentsList);
    }

    @GetMapping("/comments/{rootId}/replies")  //2.获取某条主评论下面的更多回复
    public Result<PageVO<CommentsVO>> queryMoreComments(@PathVariable Long rootId,
                                                        @RequestParam(defaultValue = "1") Long page,@RequestParam(defaultValue = "6") Long pageSize ){
        PageVO<CommentsVO> comments = commentsService.queryMoreComments(rootId,page,pageSize);

        return Result.success(comments);
    }

    @PostMapping("/articles/{articleId}/comments")  //3.发表评论或回复评论，登录用户可访问
    public Result postComment(@PathVariable Long articleId, @RequestBody CommentDTO commentDTO){
        Long userId = UserContext.get();
        if(userId == null){
            return Result.error(BlogConstants.ErrorCode.LOGIN_FAILED,"请先登录!");
        }

        commentsService.postComment(articleId,commentDTO);

        return Result.success();
    }

    @DeleteMapping("/comments/{commentId}")  //  4.删除评论，评论者或文章作者可访问
    public Result deleteComment(@PathVariable Long commentId){

        commentsService.deleteComment(commentId);

        return Result.success();
    }


}
