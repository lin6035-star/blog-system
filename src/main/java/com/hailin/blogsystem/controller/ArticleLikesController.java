package com.hailin.blogsystem.controller;

import com.hailin.blogsystem.service.ArticleLikesService;
import com.hailin.blogsystem.utils.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/articles/{articleId}/like")
@RequiredArgsConstructor
public class ArticleLikesController {

    private final ArticleLikesService articleLikesService;

    @PostMapping("")  //1.点赞文章
    public Result likeArticle(@PathVariable Long articleId){
        articleLikesService.likeArticle(articleId);

        return Result.success();
    }

    @DeleteMapping("")  //2.取消点赞文章
    public Result cancelLikeArticle(@PathVariable Long articleId){
        articleLikesService.cancelLikeArticle(articleId);

        return Result.success();
    }
}
