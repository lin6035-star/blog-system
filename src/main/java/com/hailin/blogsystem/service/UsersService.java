package com.hailin.blogsystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hailin.blogsystem.entity.Users;
import com.hailin.blogsystem.entity.vo.ArticleDetailVO;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.entity.vo.UsersVO;

public interface UsersService extends IService<Users> {
    UsersVO getUsersInfo();

    PageVO<ArticleDetailVO> getMyArticles(Long page,Long pageSize);
}
