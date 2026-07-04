package com.hailin.blogsystem.controller;

import com.hailin.blogsystem.entity.dto.ArticlesDTO;
import com.hailin.blogsystem.entity.vo.ArticleDetailVO;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.entity.vo.UsersVO;
import com.hailin.blogsystem.service.ArticlesService;
import com.hailin.blogsystem.service.UsersService;
import com.hailin.blogsystem.utils.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
public class UsersController {

    private final UsersService usersService;
    private final ArticlesService articlesService;

    @GetMapping("") //1.获取当前用户,甚至不用传参，因为UserContext里已经有了userId
    public Result<UsersVO> getUsersInfo(){

        UsersVO usersVO = usersService.getUsersInfo();

        return Result.success(usersVO);
    }

    @GetMapping("/articles")  //2.获取我自己的文章列表，包含草稿和隐藏文章
    public Result<PageVO<ArticleDetailVO>> getMyArticles(@RequestParam(defaultValue = "1") Long page,
                                                         @RequestParam(defaultValue = "10") Long pageSize){
        PageVO<ArticleDetailVO> articleDetailVOList = usersService.getMyArticles(page,pageSize);

        return Result.success(articleDetailVOList);
    }

    @GetMapping("/articles/{id}")  //3.获取我自己的文章详情
    public Result<ArticleDetailVO> getArticleById(@PathVariable Long id){
        ArticleDetailVO articleDetailVO = articlesService.getArticlesById(id);

        return Result.success(articleDetailVO);
    }

    @PostMapping("/articles")  //4.创建文章
    public Result writeArticle(@RequestBody ArticlesDTO articlesDTO){
        articlesService.writeArticle(articlesDTO);

        return Result.success();
    }

    @PutMapping("/articles/{id}")  //5.更新自己的文章
    public Result updateArticle(@PathVariable Long id,@RequestBody ArticlesDTO articlesDTO){
        articlesService.updateArticle(id,articlesDTO);

        return Result.success();
    }

    @DeleteMapping("/articles/{id}")  //6.删除自己的文章
    public Result deleteArticle(@PathVariable Long id){
        articlesService.deleteArticle(id);

        return Result.success();
    }

    @PatchMapping("/articles/{id}/hide")  //隐藏自己的文章
    public Result hideArticle(@PathVariable Long id){
        articlesService.hideArticle(id);

        return Result.success();
    }

    @PatchMapping("/articles/{id}/publish")  //发布自己的文章
    public Result publishArticle(@PathVariable Long id){
        articlesService.publishArticle(id);

        return Result.success();
    }
}
