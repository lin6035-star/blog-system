package com.hailin.blogsystem.controller;

import com.hailin.blogsystem.service.FollowService;
import com.hailin.blogsystem.utils.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/{id}/follow")
@RequiredArgsConstructor
public class FollowController {

    private final FollowService followService;

    @PostMapping("")  //关注用户
    public Result followUser(@PathVariable Long id){
        followService.followUser(id);

        return Result.success();
    }


    @DeleteMapping("")  //取消关注
    public Result cancelFollow(@PathVariable Long id){
        followService.cancelFollow(id);

        return Result.success();
    }
}
