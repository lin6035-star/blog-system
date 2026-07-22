package com.hailin.blogsystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hailin.blogsystem.entity.Users;
import com.hailin.blogsystem.entity.dto.UserProfileDTO;
import com.hailin.blogsystem.entity.vo.ArticleDetailVO;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.entity.vo.UserInfoVO;
import com.hailin.blogsystem.entity.vo.UserRelationVO;
import com.hailin.blogsystem.entity.vo.UsersVO;

public interface UsersService extends IService<Users> {
    UsersVO getUsersInfo();

    UsersVO updateProfile(UserProfileDTO userProfileDTO);

    PageVO<ArticleDetailVO> getMyArticles(Long page,Long pageSize,Long status);

    PageVO<ArticleDetailVO> getMyLiked(Long page, Long pageSize);

    PageVO<ArticleDetailVO> getMyFavorites(Long page, Long pageSize);

    PageVO<ArticleDetailVO> getComment(Long page, Long pageSize);

    UserInfoVO getPublicUserInfo(Long id);

    PageVO<UserRelationVO> getPublicUserFollowing(Long id, Long page, Long pageSize);

    PageVO<UserRelationVO> getPublicUserFollowers(Long id, Long page, Long pageSize);
}
