package com.hailin.blogsystem.controller;

import com.hailin.blogsystem.service.ArticleFavoritesService;
import com.hailin.blogsystem.utils.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/articles/{articleId}/favorite")
@RequiredArgsConstructor
public class ArticleFavoritesController {

    private final ArticleFavoritesService articleFavoritesService;

    @PostMapping("")  //1.收藏文章
    public Result favoriteArticle(@PathVariable Long articleId){
        articleFavoritesService.favoriteArticle(articleId);

        return Result.success();
    }

    @DeleteMapping("")  //2.取消收藏文章
    public Result cancelFavorite(@PathVariable Long articleId){
        articleFavoritesService.cancelFavorite(articleId);

        return Result.success();
    }
}
