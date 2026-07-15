package com.hailin.blogsystem.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.ArticleFavorites;
import com.hailin.blogsystem.entity.ArticleLikes;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.Category;
import com.hailin.blogsystem.entity.Users;
import com.hailin.blogsystem.entity.dto.ArticlesDTO;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.mapper.ArticleFavoritesMapper;
import com.hailin.blogsystem.mapper.ArticleLikesMapper;
import com.hailin.blogsystem.mapper.ArticlesMapper;
import com.hailin.blogsystem.mapper.CategoryMapper;
import com.hailin.blogsystem.mapper.UsersMapper;
import com.hailin.blogsystem.service.ArticlesService;
import com.hailin.blogsystem.entity.vo.ArticleDetailVO;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ArticlesServiceImpl extends ServiceImpl<ArticlesMapper, Articles> implements ArticlesService {

    private final UsersMapper usersMapper;
    private final CategoryMapper categoryMapper;
    private final ArticleLikesMapper articleLikesMapper;
    private final ArticleFavoritesMapper articleFavoritesMapper;

    @Override
    public PageVO<ArticleDetailVO> getArticles(Long page, Long pageSize, String keyword, Long categoryId, String sort) {  //1.获取公开文章列表

        Page<Articles> pageResult = lambdaQuery()
                .eq(Articles::getStatus, BlogConstants.ArticlesStatus.PUBLISHED)
                .like(keyword != null, Articles::getTitle, keyword)
                .eq(categoryId != null, Articles::getCategoryId, categoryId)
                .orderByDesc("recommend".equals(sort), Articles::getViewCount)
                .orderByDesc(!"recommend".equals(sort), Articles::getPublishedAt)
                .orderByDesc(Articles::getId)
                .page(new Page<>(page,pageSize));

        List<ArticleDetailVO> list = pageResult.getRecords()
                .stream()
                .map(ArticleDetailVO::from)
                .toList();
        fillArticleMeta(list);
        fillArticleLiked(list);
        fillArticleFavorited(list);

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

        if (article != null) {
            Long userId = UserContext.get();
            if (userId != null && !userId.equals(article.getAuthorId())) {
                int viewCount = article.getViewCount() == null ? 0 : article.getViewCount();
                article.setViewCount(viewCount + 1);
                updateById(article);
            }
        }

        ArticleDetailVO vo = ArticleDetailVO.from(article);
        fillArticleMeta(vo);
        fillArticleLiked(vo);
        fillArticleFavorited(vo);

        return vo;
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

    private void fillArticleMeta(ArticleDetailVO article) {
        if (article == null) {
            return;
        }
        fillArticleMeta(List.of(article));
    }

    private void fillArticleLiked(List<ArticleDetailVO> articles) {
        if (articles == null || articles.isEmpty()) {
            return;
        }

        Long currentUserId = UserContext.get();
        if (currentUserId == null) {
            return;
        }

        List<Long> articleIds = articles.stream()
                .map(ArticleDetailVO::getId)
                .filter(Objects::nonNull)
                .toList();

        if (articleIds.isEmpty()) {
            return;
        }

        Set<Long> likedArticleIds = articleLikesMapper.selectList(
                        new LambdaQueryWrapper<ArticleLikes>()
                                .in(ArticleLikes::getArticleId, articleIds)
                                .eq(ArticleLikes::getUserId, currentUserId))
                .stream()
                .map(ArticleLikes::getArticleId)
                .collect(Collectors.toSet());

        for (ArticleDetailVO article : articles) {
            article.setLiked(likedArticleIds.contains(article.getId()) ? 1 : 0);
        }
    }

    private void fillArticleLiked(ArticleDetailVO article) {
        if (article == null) {
            return;
        }
        fillArticleLiked(List.of(article));
    }

    private void fillArticleFavorited(List<ArticleDetailVO> articles) {
        if (articles == null || articles.isEmpty()) {
            return;
        }

        Long currentUserId = UserContext.get();
        if (currentUserId == null) {
            return;
        }

        List<Long> articleIds = articles.stream()
                .map(ArticleDetailVO::getId)
                .filter(Objects::nonNull)
                .toList();

        if (articleIds.isEmpty()) {
            return;
        }

        Set<Long> favoritedArticleIds = articleFavoritesMapper.selectList(
                        new LambdaQueryWrapper<ArticleFavorites>()
                                .in(ArticleFavorites::getArticleId, articleIds)
                                .eq(ArticleFavorites::getUserId, currentUserId))
                .stream()
                .map(ArticleFavorites::getArticleId)
                .collect(Collectors.toSet());

        for (ArticleDetailVO article : articles) {
            article.setFavorited(favoritedArticleIds.contains(article.getId()) ? 1 : 0);
        }
    }

    private void fillArticleFavorited(ArticleDetailVO article) {
        if (article == null) {
            return;
        }
        fillArticleFavorited(List.of(article));
    }

    private void fillArticleMeta(List<ArticleDetailVO> articles) {
        if (articles == null || articles.isEmpty()) {
            return;
        }

        List<Long> authorIds = articles.stream()
                .map(ArticleDetailVO::getAuthorId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<Long> categoryIds = articles.stream()
                .map(ArticleDetailVO::getCategoryId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<Long, Users> usersById = authorIds.isEmpty()
                ? Map.of()
                : usersMapper.selectBatchIds(authorIds)
                .stream()
                .collect(Collectors.toMap(Users::getId, Function.identity()));
        Map<Long, Category> categoriesById = categoryIds.isEmpty()
                ? Map.of()
                : categoryMapper.selectBatchIds(categoryIds)
                .stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));

        for (ArticleDetailVO article : articles) {
            Users author = usersById.get(article.getAuthorId());
            if (author != null) {
                article.setAuthorName(author.getNickname());
            }

            Category category = categoriesById.get(article.getCategoryId());
            if (category != null) {
                article.setCategoryName(category.getName());
            }
        }
    }


    @Override
    public Long writeArticle(ArticlesDTO articlesDTO) {
        Articles articles = new Articles();
        BeanUtil.copyProperties(articlesDTO,articles);

        articles.setAuthorId(UserContext.get());
        articles.setCreatedAt(LocalDateTime.now());
        articles.setUpdatedAt(LocalDateTime.now());
        articles.setPublishedAt(null);
        syncPublishedAt(articles);

        save(articles);
        return articles.getId();
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

        LocalDateTime previousPublishedAt = articles.getPublishedAt();
        BeanUtil.copyProperties(articlesDTO,articles);

        articles.setUpdatedAt(LocalDateTime.now());
        articles.setAuthorId(UserContext.get());
        articles.setPublishedAt(previousPublishedAt);
        syncPublishedAt(articles);

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

    private void syncPublishedAt(Articles articles) {
        if (Objects.equals(articles.getStatus(), BlogConstants.ArticlesStatus.PUBLISHED)
                && articles.getPublishedAt() == null) {
            articles.setPublishedAt(LocalDateTime.now());
        }
    }
}
