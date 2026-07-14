package com.hailin.blogsystem.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hailin.blogsystem.entity.ArticleComments;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.CommentLikes;
import com.hailin.blogsystem.entity.Users;
import com.hailin.blogsystem.entity.dto.CommentDTO;
import com.hailin.blogsystem.entity.vo.CommentsVO;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.mapper.ArticlesMapper;
import com.hailin.blogsystem.mapper.CommentsMapper;
import com.hailin.blogsystem.mapper.LikeCommentsMapper;
import com.hailin.blogsystem.mapper.UsersMapper;
import com.hailin.blogsystem.service.CommentsService;
import com.hailin.blogsystem.service.IpLocationService;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CommentsServiceImpl extends ServiceImpl<CommentsMapper, ArticleComments> implements CommentsService {

    private final UsersMapper usersMapper;
    private final LikeCommentsMapper likeCommentsMapper;
    private final ArticlesMapper articlesMapper;
    private final IpLocationService ipLocationService;

    @Override  //1.获取文章评论列表，游客可访问，每条主评论带前几条回复
    public PageVO<CommentsVO> getComments(Long articleId, Long page, Long pageSize, String sort) {

        Page<ArticleComments> pageResult = lambdaQuery()
                .eq(ArticleComments::getArticleId, articleId)
                .orderByDesc("hot".equals(sort),ArticleComments::getLikeCount)
                .orderByDesc(ArticleComments::getCreatedAt)
                .isNull(ArticleComments::getParentId)
                .page(new Page<>(page, pageSize));

        List<CommentsVO> list = pageResult.getRecords()
                .stream()
                .map(CommentsVO::from)
                .toList();  //这一步只是把评论表字段转换成VO，但是还有一些字段是别的表的，要通过查询别的表才能查询到

        //从VO里收集所有的userId
        fillUserInfo(list);

        //关于liked字段
        fillLiked(list);

        //关于replies字段
        List<Long> rootIds = list.stream()
                .map(CommentsVO::getId)
                .filter(Objects::nonNull)
                .toList();

        if (list.isEmpty()) {
            return new PageVO<>(list, pageResult.getTotal(), page, pageSize);
        }

        List<ArticleComments> allReplies = lambdaQuery()
                .in(ArticleComments::getRootId, rootIds)
                .orderByAsc(ArticleComments::getCreatedAt)
                .list();

        Map<Long, List<ArticleComments>> repliesByRootId = allReplies.stream()
                .collect(Collectors.groupingBy(ArticleComments::getRootId));

        for (CommentsVO mainComment : list) {
            List<ArticleComments> replyEntities = repliesByRootId.getOrDefault(
                    mainComment.getId(),
                    List.of()
            );

            List<CommentsVO> replies = replyEntities.stream()
                    .limit(4)
                    .map(CommentsVO::from)
                    .toList();

            mainComment.setReplies(replies);
            mainComment.setReplyCount((long) replyEntities.size());
        }

        for(CommentsVO comment : list){
            List<CommentsVO> replies = comment.getReplies();
            if(replies != null){
                fillUserInfo(replies);
                fillReplyToNickname(replies);
                fillLiked(replies);
            }
        }

        return new PageVO<>(
                list,
                pageResult.getTotal(),
                page,
                pageSize
        );
    }


    @Override  //2.获取某条主评论下面的更多回复
    public PageVO<CommentsVO> queryMoreComments(Long rootId, Long page, Long pageSize) {

        Page<ArticleComments> pageResult = lambdaQuery().eq(ArticleComments::getRootId, rootId)
                .page(new Page<>(page, pageSize));

        List<CommentsVO> list = pageResult.getRecords().stream()
                .map(CommentsVO::from)
                .toList();

        fillUserInfo(list);
        fillReplyToNickname(list);
        fillLiked(list);

        //关于replies字段
        List<Long> rootIds = list.stream()
                .map(CommentsVO::getId)
                .filter(Objects::nonNull)
                .toList();

        if (list.isEmpty()) {
            return new PageVO<>(list, pageResult.getTotal(), page, pageSize);
        }

        List<ArticleComments> allReplies = lambdaQuery()
                .in(ArticleComments::getRootId, rootIds)
                .orderByAsc(ArticleComments::getCreatedAt)
                .list();

        Map<Long, List<ArticleComments>> repliesByRootId = allReplies.stream()
                .collect(Collectors.groupingBy(ArticleComments::getRootId));

        for (CommentsVO mainComment : list) {
            List<ArticleComments> replyEntities = repliesByRootId.getOrDefault(
                    mainComment.getId(),
                    List.of()
            );

            List<CommentsVO> replies = replyEntities.stream()
                    .map(CommentsVO::from)
                    .toList();

            mainComment.setReplies(replies);
            mainComment.setReplyCount((long) replyEntities.size());
        }

        for(CommentsVO comment : list){
            List<CommentsVO> replies = comment.getReplies();
            if(replies != null){
                fillUserInfo(replies);
                fillReplyToNickname(replies);
                fillLiked(replies);
            }
        }

        return new PageVO<>(
                list,
                pageResult.getTotal(),
                page,
                pageSize
        );
    }


    @Override  //3.发表评论或回复评论，登录用户可访问
    public void postComment(Long articleId, CommentDTO commentDTO, String clientIp,
                            String cloudflareCountryCode) {
        ArticleComments articleComments = new ArticleComments();
        BeanUtil.copyProperties(commentDTO,articleComments);

        Long parentId = commentDTO.getParentId();
        if (parentId == null) {
            articleComments.setRootId(null);
        } else {
            ArticleComments parentComment = getById(parentId);
            if (parentComment == null) {
                throw new IllegalArgumentException("父评论不存在");
            }
            if (!articleId.equals(parentComment.getArticleId())) {
                throw new IllegalArgumentException("父评论不属于当前文章");
            }

            articleComments.setRootId(
                    parentComment.getRootId() == null
                            ? parentComment.getId()
                            : parentComment.getRootId()
            );
        }

        articleComments.setArticleId(articleId);
        articleComments.setCreatedAt(LocalDateTime.now());
        articleComments.setUserId(UserContext.get());
        articleComments.setLikeCount(0L);
        articleComments.setIp(clientIp);
        articleComments.setIpLocation(ipLocationService.getLocation(clientIp, cloudflareCountryCode));

        save(articleComments);
    }


    @Override  //4.删除评论，评论者或文章作者可访问
    public void deleteComment(Long commentId) {
        ArticleComments comment = getById(commentId);
        Long userId = UserContext.get();
        if (comment == null) {
            throw new IllegalArgumentException("评论不存在");
        }
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }

        Articles article = articlesMapper.selectById(comment.getArticleId());
        if (article == null) {
            throw new IllegalArgumentException("文章不存在");
        }

        if(!userId.equals(comment.getUserId()) && !userId.equals(article.getAuthorId())){
            throw new IllegalArgumentException("暂无权限");
        }

        List<Long> commentIds = new ArrayList<>();
        commentIds.add(commentId);
        commentIds.addAll(findDescendantCommentIds(commentId));

        lambdaUpdate()
                .set(ArticleComments::getDeletedAt, LocalDateTime.now())
                .set(ArticleComments::getDeletedBy, userId)
                .in(ArticleComments::getId, commentIds)
                .update();
    }

    private List<Long> findDescendantCommentIds(Long commentId) {
        List<Long> descendants = new ArrayList<>();
        List<Long> currentLevelIds = List.of(commentId);

        while (!currentLevelIds.isEmpty()) {
            List<Long> childIds = lambdaQuery()
                    .select(ArticleComments::getId)
                    .in(ArticleComments::getParentId, currentLevelIds)
                    .list()
                    .stream()
                    .map(ArticleComments::getId)
                    .toList();

            if (childIds.isEmpty()) {
                break;
            }

            descendants.addAll(childIds);
            currentLevelIds = childIds;
        }

        return descendants;
    }


    //这一块全是批量查询
    //关于avatarUser和nickname字段
    public void fillUserInfo(List<CommentsVO> list){
        List<Long> userIds = list.stream().map(CommentsVO::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<Users> users = new ArrayList<>();
        if (!userIds.isEmpty()) {
            users = usersMapper.selectBatchIds(userIds);
        }
        Map<Long, Users> usersById = users.stream().collect(Collectors.toMap(Users::getId, Function.identity()));

        for(CommentsVO comment : list){
            Users user = usersById.get(comment.getUserId());
            if (user != null) {
                comment.setNickname(user.getNickname());
                comment.setAvatarUrl(user.getAvatarUrl());
            }
        }
    }

    //关于replyToNickname字段
    public void fillReplyToNickname(List<CommentsVO> list){
        List<Long> parentIds = list.stream().map(CommentsVO::getParentId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<ArticleComments> parentComments = new ArrayList<>();
        if (!parentIds.isEmpty()) {
            parentComments = listByIds(parentIds);  //然后查询到该parentId的评论
        }
        Map<Long, ArticleComments> parentCommentById = parentComments.stream()
                .collect(Collectors.toMap(ArticleComments::getId, Function.identity()));

        List<Long> replyToUserIds = parentComments.stream()
                .map(ArticleComments::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();   //接着通过这些评论就可以拿到userId

        Map<Long, Users> replyToUsersById = replyToUserIds.isEmpty()
                ? Map.of()
                : usersMapper.selectBatchIds(replyToUserIds)
                .stream()
                .collect(Collectors.toMap(Users::getId, Function.identity()));

        for (CommentsVO comment : list) {  //拿到userId就可以拿到nickname了
            if (comment.getParentId() == null) {
                comment.setReplyToNickname(null);
                continue;
            }

            ArticleComments parentComment = parentCommentById.get(comment.getParentId());
            if (parentComment == null) {
                comment.setReplyToNickname(null);
                continue;
            }

            Users replyToUser = replyToUsersById.get(parentComment.getUserId());
            if (replyToUser != null) {
                comment.setReplyToNickname(replyToUser.getNickname());
            }
        }
    }

    //关于liked字段
    public void fillLiked(List<CommentsVO> list){
        Long currentUserId = UserContext.get();

        if(list == null || list.isEmpty() || currentUserId == null){
            return;
        }
        List<Long> commentIds = list.stream().map(CommentsVO::getId)
                .toList();

        if(commentIds.isEmpty()){
            return;
        }
        LambdaQueryWrapper<CommentLikes> lambdaQueryWrapper  =new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(CommentLikes::getUserId,currentUserId)
                .in(CommentLikes::getCommentId,commentIds);

        List<CommentLikes> commentLikes = likeCommentsMapper.selectList(lambdaQueryWrapper);

        Set<Long> likedCommentIds = commentLikes.stream()
                .map(CommentLikes::getCommentId)
                .collect(Collectors.toSet());

        for(CommentsVO comment : list){
            comment.setLiked(likedCommentIds.contains(comment.getId()));
        }
    }
}
