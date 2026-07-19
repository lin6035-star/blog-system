package com.hailin.blogsystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hailin.blogsystem.entity.UserFollows;

public interface FollowService extends IService<UserFollows> {
    void followUser(Long id);

    void cancelFollow(Long id);
}
