package com.hailin.blogsystem.controller;

import com.hailin.blogsystem.entity.vo.ArticleDetailVO;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.entity.vo.UserInfoVO;
import com.hailin.blogsystem.entity.vo.UserRelationVO;
import com.hailin.blogsystem.service.ArticlesService;
import com.hailin.blogsystem.service.UsersService;
import com.hailin.blogsystem.utils.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/{id}")
@RequiredArgsConstructor
public class UserInfoController {

    private final UsersService usersService;
    private final ArticlesService articlesService;

    @GetMapping("")  //1.获取用户资料
    public Result<UserInfoVO> getPublicUserInfo(@PathVariable Long id){
        UserInfoVO userInfoVO = usersService.getPublicUserInfo(id);

        return Result.success(userInfoVO);
    }

    @GetMapping("/articles")  //2.默认加载：他发布的文章
    public Result<PageVO<ArticleDetailVO>> getPublicUserArticles(@PathVariable Long id, @RequestParam(defaultValue = "1") Long page,
                                                                 @RequestParam(defaultValue = "3") Long pageSize){
        PageVO<ArticleDetailVO> pageResult = articlesService.getPublicUserArticles(id,page,pageSize);

        return Result.success(pageResult);
    }

    @GetMapping("/liked")  //3.查看他喜欢的文章
    public Result<PageVO<ArticleDetailVO>> getPublicUserLiked(@PathVariable Long id, @RequestParam(defaultValue = "1") Long page,
                                                              @RequestParam(defaultValue = "3") Long pageSize){
        PageVO<ArticleDetailVO> pageResult = articlesService.getPublicUserLiked(id,page,pageSize);

        return Result.success(pageResult);
    }

    @GetMapping("/favorited")  //4.查看他收藏的文章
    public Result<PageVO<ArticleDetailVO>> getPublicUserFavorited(@PathVariable Long id, @RequestParam(defaultValue = "1") Long page,
                                                                  @RequestParam(defaultValue = "3") Long pageSize){
        PageVO<ArticleDetailVO> pageResult = articlesService.getPublicUserFavorited(id,page,pageSize);

        return Result.success(pageResult);
    }

    @GetMapping("/commented")  //5.查看他评论过的文章
    public Result<PageVO<ArticleDetailVO>> getPublicUserCommented(@PathVariable Long id, @RequestParam(defaultValue = "1") Long page,
                                                                  @RequestParam(defaultValue = "3") Long pageSize){
        PageVO<ArticleDetailVO> pageResult = articlesService.getPublicCommented(id,page,pageSize);

        return Result.success(pageResult);
    }

    @GetMapping("/following")  //6.查看他关注了谁
    public Result<PageVO<UserRelationVO>> getPublicUserFollowing(@PathVariable Long id, @RequestParam(defaultValue = "1") Long page,
                                                                 @RequestParam(defaultValue = "10") Long pageSize){
        PageVO<UserRelationVO> pageResult = usersService.getPublicUserFollowing(id,page,pageSize);

        return Result.success(pageResult);
    }

    @GetMapping("/followers")  //7.查看谁关注了他
    public Result<PageVO<UserRelationVO>> getPublicUserFollowers(@PathVariable Long id, @RequestParam(defaultValue = "1") Long page,
                                                                 @RequestParam(defaultValue = "10") Long pageSize){
        PageVO<UserRelationVO> pageResult = usersService.getPublicUserFollowers(id,page,pageSize);

        return Result.success(pageResult);
    }
}
