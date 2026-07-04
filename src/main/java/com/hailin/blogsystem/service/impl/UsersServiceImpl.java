package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.Users;
import com.hailin.blogsystem.entity.vo.ArticleDetailVO;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.entity.vo.UsersVO;
import com.hailin.blogsystem.mapper.UsersMapper;
import com.hailin.blogsystem.service.ArticlesService;
import com.hailin.blogsystem.service.UsersService;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UsersServiceImpl extends ServiceImpl<UsersMapper, Users>
        implements UsersService {

    @Override  //获取当前用户
    public UsersVO getUsersInfo() {
        Long userId = UserContext.get();

        if(userId == null){
            throw new IllegalArgumentException("请先登录");
        }

        Users users = getById(userId);
        if(users == null){
            throw new IllegalArgumentException("该用户不存在，出现错误");
        }

        return UsersVO.from(users);
    }

    private final ArticlesService articlesService;

    @Override //获取我自己的文章列表，包含草稿和隐藏文章
    public PageVO<ArticleDetailVO> getMyArticles(Long page,Long pageSize) {
        Long userId = UserContext.get();

        if(userId == null){
            throw new IllegalArgumentException("请先登录");
        }

        Users users = getById(userId);
        if(users == null){
            throw new IllegalArgumentException("该用户不存在，出现错误");
        }

        Page<Articles> pageResult = articlesService.lambdaQuery()
                .eq(Articles::getAuthorId,userId)
                .orderByDesc(Articles::getCreatedAt)
                .page(new Page<>(page,pageSize));

        List<ArticleDetailVO> list = pageResult.getRecords()
                .stream()
                .map(ArticleDetailVO::from)
                .toList();

           return new PageVO<>(
                   list,
                   pageResult.getTotal(),
                   page,
                   pageSize
           );
    }
}
