package com.hailin.blogsystem;

import com.hailin.blogsystem.entity.ArticleComments;
import com.hailin.blogsystem.entity.vo.ArticleDetailVO;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.mapper.CommentsMapper;
import com.hailin.blogsystem.service.UsersService;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class UsersServiceTests {

    @Autowired
    private UsersService usersService;

    @Autowired
    private CommentsMapper commentsMapper;

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void commentedArticlesAreDistinctAndOrderedByLatestCommentTime() {
        commentsMapper.insert(comment(3001L, 1L, "2026-07-10T10:00:00"));
        commentsMapper.insert(comment(3002L, 3L, "2026-07-12T10:00:00"));
        commentsMapper.insert(comment(3003L, 1L, "2026-07-13T10:00:00"));
        UserContext.set(101L);

        PageVO<ArticleDetailVO> firstPage = usersService.getComment(1L, 1L);
        PageVO<ArticleDetailVO> secondPage = usersService.getComment(2L, 1L);

        assertThat(firstPage.getTotal()).isEqualTo(2L);
        assertThat(firstPage.getList()).extracting(ArticleDetailVO::getId).containsExactly(1L);
        assertThat(secondPage.getTotal()).isEqualTo(2L);
        assertThat(secondPage.getList()).extracting(ArticleDetailVO::getId).containsExactly(3L);
    }

    private ArticleComments comment(Long id, Long articleId, String createdAt) {
        ArticleComments comment = new ArticleComments();
        comment.setId(id);
        comment.setArticleId(articleId);
        comment.setUserId(101L);
        comment.setContent("comment " + id);
        comment.setLikeCount(0L);
        comment.setCreatedAt(LocalDateTime.parse(createdAt));
        return comment;
    }
}
