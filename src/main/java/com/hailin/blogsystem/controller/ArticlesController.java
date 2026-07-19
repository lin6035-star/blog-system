package com.hailin.blogsystem.controller;

import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.service.ArticlesService;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.utils.Result;
import com.hailin.blogsystem.entity.vo.ArticleDetailVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController()
@RequestMapping("/api")
@RequiredArgsConstructor
public class ArticlesController {

    private final ArticlesService articlesService;

    @GetMapping("/articles")  //1.获取公开文章列表
    public Result<PageVO<ArticleDetailVO>> getArticles(@RequestParam(defaultValue = "1") Long page,
                                                       @RequestParam(defaultValue = "10") Long pageSize,
                                                       @RequestParam(required = false) String keyword,
                                                       @RequestParam(required = false) Long categoryId,
                                                       @RequestParam(defaultValue = "latest") String sort){
        PageVO<ArticleDetailVO> data = articlesService.getArticles(page,pageSize,keyword,categoryId,sort);

        return Result.success(data);
    }

    @GetMapping("/articles/{id}")  //2.获取公开文章详情
    public Result getArticlesById(@PathVariable Long id){
        ArticleDetailVO article = articlesService.getPublicArticleById(id);
        if (article == null) {
            return Result.error(BlogConstants.ErrorCode.NOT_FOUND, "article not found");
        }

        return Result.success(article);
    }

    @GetMapping("/articles/hot")  //3.获取热度前十的文章
    public Result<PageVO<ArticleDetailVO>> getHotArticles(@RequestParam(defaultValue = "1") Long page,
                                                          @RequestParam(defaultValue = "10") Long pageSize){
        PageVO<ArticleDetailVO> data = articlesService.getHotArticles(page, pageSize);
        return Result.success(data);
    }
}
