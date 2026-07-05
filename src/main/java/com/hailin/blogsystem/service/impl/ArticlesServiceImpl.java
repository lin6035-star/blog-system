package com.hailin.blogsystem.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.dto.ArticlesDTO;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.mapper.ArticlesMapper;
import com.hailin.blogsystem.service.ArticlesService;
import com.hailin.blogsystem.entity.vo.ArticleDetailVO;
import com.hailin.blogsystem.utils.UserContext;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ArticlesServiceImpl extends ServiceImpl<ArticlesMapper, Articles> implements ArticlesService {

    @Override
    public PageVO<ArticleDetailVO> getArticles(Long page, Long pageSize) {  //1.获取公开文章列表

        Page<Articles> pageResult = lambdaQuery()
                .eq(Articles::getStatus,BlogConstants.ArticlesStatus.PUBLISHED)
                .eq(Articles::getStatus,BlogConstants.ArticlesStatus.PUBLISHED)
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

    @Override
    public ArticleDetailVO getPublicArticleById(Long id) {  //2.获取公开文章详情
        Articles article = lambdaQuery()
                .eq(Articles::getId, id)
                .eq(Articles::getStatus, BlogConstants.ArticlesStatus.PUBLISHED)
                .one();

        return ArticleDetailVO.from(article);
    }


    @Override
    public ArticleDetailVO getArticlesById(Long id) {
        Articles articles = getById(id);
        if(articles == null){
            throw new IllegalArgumentException("未找到该博文");
        }
        Long userId = UserContext.get();
        if(!articles.getAuthorId().equals(userId)){
            throw new IllegalArgumentException("无权操作该文章");
        }


        ArticleDetailVO articlesVO = new ArticleDetailVO();

        BeanUtil.copyProperties(articles,articlesVO);

        return articlesVO;
    }


    @Override
    public void writeArticle(ArticlesDTO articlesDTO) {
        Articles articles = new Articles();
        BeanUtil.copyProperties(articlesDTO,articles);

        articles.setAuthorId(UserContext.get());
        articles.setCreatedAt(LocalDateTime.now());
        articles.setUpdatedAt(LocalDateTime.now());

        save(articles);
    }


    @Override
    public void updateArticle(Long id, ArticlesDTO articlesDTO) {

        Articles articles = getById(id);
        if(articles == null){
            throw new IllegalArgumentException("未找到该博文");
        }
        Long userId = UserContext.get();
        if(!articles.getAuthorId().equals(userId)){
            throw new IllegalArgumentException("无权操作该文章");
        }

        BeanUtil.copyProperties(articlesDTO,articles);

        articles.setUpdatedAt(LocalDateTime.now());
        articles.setAuthorId(UserContext.get());

        updateById(articles);
    }


    @Override
    public void deleteArticle(Long id) {
        Articles articles = getById(id);
        if(articles == null){
            throw new IllegalArgumentException("未找到该博文");
        }
        Long userId = UserContext.get();
        if(!articles.getAuthorId().equals(userId)){
            throw new IllegalArgumentException("无权操作该文章");
        }

        removeById(id);
    }


    @Override
    public void hideArticle(Long id) {
        Articles articles = getById(id);
        if(articles == null){
            throw  new IllegalArgumentException("该文章不存在!");
        }

        Long userId = UserContext.get();
        if(!articles.getAuthorId().equals(userId)){
            throw new IllegalArgumentException("无权操作该文章");
        }

        articles.setStatus(BlogConstants.ArticlesStatus.HIDDEN);
        articles.setUpdatedAt(LocalDateTime.now());

        updateById(articles);
    }


    @Override
    public void publishArticle(Long id) {
        Articles articles = getById(id);
        if(articles == null){
            throw  new IllegalArgumentException("该文章不存在!");
        }
        Long userId = UserContext.get();
        if(!articles.getAuthorId().equals(userId)){
            throw new IllegalArgumentException("无权操作该文章");
        }


        articles.setStatus(BlogConstants.ArticlesStatus.PUBLISHED);
        articles.setUpdatedAt(LocalDateTime.now());
        articles.setPublishedAt(LocalDateTime.now());

        updateById(articles);
    }
}
