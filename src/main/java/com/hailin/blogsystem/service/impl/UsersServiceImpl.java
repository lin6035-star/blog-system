package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.*;
import com.hailin.blogsystem.entity.dto.UserProfileDTO;
import com.hailin.blogsystem.entity.vo.ArticleDetailVO;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.entity.vo.UsersVO;
import com.hailin.blogsystem.mapper.*;
import com.hailin.blogsystem.service.ArticlesService;
import com.hailin.blogsystem.service.UsersService;
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
public class UsersServiceImpl extends ServiceImpl<UsersMapper, Users>
        implements UsersService {

    private final ArticleLikesMapper articleLikesMapper;
    private final CommentsMapper commentsMapper;
    private final ArticleFavoritesMapper articleFavoritesMapper;
    private final CategoryMapper categoryMapper;
    private final UsersMapper usersMapper;

    @Override  //1.获取当前用户
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

    @Override // 修改当前用户昵称和个人简介
    public UsersVO updateProfile(UserProfileDTO userProfileDTO) {
        Long userId = UserContext.get();

        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }

        Users users = getById(userId);
        if (users == null) {
            throw new IllegalArgumentException("该用户不存在，出现错误");
        }

        if (userProfileDTO == null) {
            throw new IllegalArgumentException("请求参数不能为空");
        }

        String nickname = userProfileDTO.getNickname() == null ? "" : userProfileDTO.getNickname().trim();
        if (nickname.isEmpty()) {
            throw new IllegalArgumentException("昵称不能为空");
        }

        String bio = userProfileDTO.getBio() == null ? "" : userProfileDTO.getBio().trim();
        LocalDateTime updatedAt = LocalDateTime.now();
        lambdaUpdate()
                .eq(Users::getId, userId)
                .set(Users::getNickname, nickname)
                .set(Users::getBio, bio)
                .set(Users::getUpdatedAt, updatedAt)
                .update();

        users.setNickname(nickname);
        users.setBio(bio);
        users.setUpdatedAt(updatedAt);
        return UsersVO.from(users);
    }

    private final ArticlesService articlesService;

    @Override //2.获取我自己的文章列表，包含草稿和隐藏文章
    public PageVO<ArticleDetailVO> getMyArticles(Long page,Long pageSize,Long status) {
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
                .eq(status!=null,Articles::getStatus,status)
                .ne(status==null,Articles::getStatus,BlogConstants.ArticlesStatus.DRAFT)
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


    @Override  //3.获取我喜欢的文章的列表
    public PageVO<ArticleDetailVO> getMyLiked(Long page, Long pageSize) {
        Long userId = UserContext.get();

        if(userId == null){
            throw new IllegalArgumentException("请先登录");
        }

        Users users = getById(userId);
        if(users == null){
            throw new IllegalArgumentException("该用户不存在，出现错误");
        }

        Page<ArticleLikes> articleLikesPage = articleLikesMapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<ArticleLikes>().eq(ArticleLikes::getUserId, userId)
                        .orderByDesc(ArticleLikes::getCreateTime));

        List<Long> articleIds = articleLikesPage.getRecords().stream()
                .map(ArticleLikes::getArticleId)
                .toList();  //拿这一页的文章 id

        if (articleIds.isEmpty()) {
            return new PageVO<>(List.of(), 0L, page, pageSize);
        }
        // 再查文章
        List<Articles> articles = articlesService.lambdaQuery()
                .in(Articles::getId, articleIds)
                .list();
        //in 查出来的顺序不一定等于 articleIds 的顺序，所以要转 Map，再按 articleIds 顺序组装
        Map<Long,Articles> articleMap = articles.stream()
                .collect(Collectors.toMap(Articles::getId, Function.identity()));

        List<ArticleDetailVO> list = articleIds.stream()
                .map(articleMap::get)
                .map(ArticleDetailVO::from)
                .filter(Objects::nonNull)
                .toList();
        fillArticleMeta(list);
        fillArticleLiked(list);
        fillArticleFavorited(list);

        return new PageVO<>(
                list,
                articleLikesPage.getTotal(),
                page,
                pageSize
        );
    }

    @Override  //4.查询自己收藏的文章
    public PageVO<ArticleDetailVO> getMyFavorites(Long page, Long pageSize) {
        Long userId = UserContext.get();

        if(userId == null){
            throw new IllegalArgumentException("请先登录");
        }

        Users users = getById(userId);
        if(users == null){
            throw new IllegalArgumentException("该用户不存在，出现错误");
        }

        Page<ArticleFavorites> articleFavoritesPage = articleFavoritesMapper.selectPage(new Page<>(page,pageSize),new LambdaQueryWrapper<ArticleFavorites>()
                .eq(ArticleFavorites::getUserId, userId)
                .orderByDesc(ArticleFavorites::getCreateTime));

        List<Long> articleIds = articleFavoritesPage.getRecords().stream()
                .map(ArticleFavorites::getArticleId)
                .toList();


        if (articleIds.isEmpty()) {
            return new PageVO<>(List.of(), 0L, page, pageSize);
        }

        List<Articles> articles = articlesService.lambdaQuery()
                .in(Articles::getId, articleIds)
                .list();

        Map<Long, Articles> articleMap = articles.stream()
                .collect(Collectors.toMap(Articles::getId, Function.identity()));

        List<ArticleDetailVO> list = articleIds.stream()
                .map(articleMap::get)
                .filter(Objects::nonNull)
                .map(ArticleDetailVO::from)
                .toList();

        fillArticleMeta(list);
        fillArticleLiked(list);
        fillArticleFavorited(list);

        return new PageVO<>(
                list,
                articleFavoritesPage.getTotal(),
                page,
                pageSize
        );
    }


    @Override  //5.我评价过的文章
    public PageVO<ArticleDetailVO> getComment(Long page, Long pageSize) {
        Long userId = UserContext.get();

        if(userId == null){
            throw new IllegalArgumentException("请先登录");
        }

        Users users = getById(userId);
        if(users == null){
            throw new IllegalArgumentException("该用户不存在，出现错误");
        }
        List<ArticleComments> comments = commentsMapper.selectList(
                new LambdaQueryWrapper<ArticleComments>()
                        .eq(ArticleComments::getUserId, userId)
                        .orderByDesc(ArticleComments::getCreatedAt)
        );

        List<Long> articleIds = comments.stream()
                .map(ArticleComments::getArticleId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (articleIds.isEmpty()) {
            return new PageVO<>(List.of(), 0L, page, pageSize);
        }

        int from = (int) ((page - 1) * pageSize);
        int to = Math.min(from + pageSize.intValue(), articleIds.size());

        if (from >= articleIds.size()) {
            return new PageVO<>(List.of(), (long) articleIds.size(), page, pageSize);
        }

        List<Long> pageArticleIds = articleIds.subList(from, to);

        List<Articles> articles = articlesService.lambdaQuery()
                .in(Articles::getId, pageArticleIds)
                .list();

        Map<Long, Articles> articleMap = articles.stream()
                .collect(Collectors.toMap(Articles::getId, Function.identity()));

        List<ArticleDetailVO> list = pageArticleIds.stream()
                .map(articleMap::get)
                .filter(Objects::nonNull)
                .map(ArticleDetailVO::from)
                .toList();

        fillArticleMeta(list);
        fillArticleLiked(list);
        fillArticleFavorited(list);

        return new PageVO<>(
                list,
                (long) articleIds.size(),
                page,
                pageSize
        );
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

}
