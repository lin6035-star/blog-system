package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hailin.blogsystem.entity.UserFollows;
import com.hailin.blogsystem.entity.Users;
import com.hailin.blogsystem.mapper.FollowMapper;
import com.hailin.blogsystem.mapper.UsersMapper;
import com.hailin.blogsystem.service.FollowService;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.cglib.core.Local;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class FollowServiceImpl extends ServiceImpl<FollowMapper, UserFollows>
implements FollowService {

    private final UsersMapper usersMapper;

    @Override  //关注用户
    @Transactional
    public void followUser(Long followingId) {
        Long userId = UserContext.get();

        if(userId == null){
            throw new IllegalArgumentException("请先登录再操作亲");
        }

        if (userId.equals(followingId)) {
            throw new IllegalArgumentException("不能关注自己");
        }

        Users followingUser = usersMapper.selectById(followingId);
        if (followingUser == null) {
            throw new IllegalArgumentException("用户不存在");
        }

        UserFollows history = lambdaQuery()
                .eq(UserFollows::getFollowerId, userId)
                .eq(UserFollows::getFollowingId, followingId)
                .one();

        UserFollows userFollows = new UserFollows();

        if(history == null){
            userFollows.setFollowerId(userId);
            userFollows.setFollowingId(followingId);
            userFollows.setCreatedAt(LocalDateTime.now());
            userFollows.setUpdatedAt(LocalDateTime.now());

            save(userFollows);
            usersMapper.update(null,
                    new LambdaUpdateWrapper<Users>()
                            .eq(Users::getId,userId)
                            .setSql("following_count = GREATEST(following_count + 1, 0)"));
            usersMapper.update(null,
                    new LambdaUpdateWrapper<Users>()
                            .eq(Users::getId,followingId)
                            .setSql("followers_count = GREATEST(followers_count + 1,0)"));

            return;
        }

        history.setUpdatedAt(LocalDateTime.now());
        history.setDeletedAt(null);

        updateById(history);

        usersMapper.update(null,
                new LambdaUpdateWrapper<Users>()
                        .eq(Users::getId,userId)
                        .setSql("following_count = GREATEST(following_count + 1, 0)"));

        usersMapper.update(null,
                new LambdaUpdateWrapper<Users>()
                        .eq(Users::getId,followingId)
                        .setSql("followers_count = GREATEST(followers_count + 1,0)"));

    }


    @Override  //取消关注
    @Transactional
    public void cancelFollow(Long followingId) {
        Long userId = UserContext.get();

        if(userId == null){
            throw new IllegalArgumentException("请先登录再操作亲");
        }

        if (userId.equals(followingId)) {
            throw new IllegalArgumentException("不能关注自己");
        }

        UserFollows history = lambdaQuery()
                .eq(UserFollows::getFollowerId, userId)
                .eq(UserFollows::getFollowingId, followingId)
                .isNull(UserFollows::getDeletedAt)
                .one();

        if(history == null){
            throw new IllegalArgumentException("尚未关注该用户");
        }

        history.setDeletedAt(LocalDateTime.now());
        history.setUpdatedAt(LocalDateTime.now());

        updateById(history);

        usersMapper.update(null,
                new LambdaUpdateWrapper<Users>()
                        .eq(Users::getId,userId)
                        .setSql("following_count = GREATEST(following_count - 1, 0)"));

        usersMapper.update(null,
                new LambdaUpdateWrapper<Users>()
                        .eq(Users::getId,followingId)
                        .setSql("followers_count = GREATEST(followers_count - 1,0)"));
    }
}
