package com.hailin.blogsystem;

import com.hailin.blogsystem.entity.ArticleComments;
import com.hailin.blogsystem.entity.dto.CommentDTO;
import com.hailin.blogsystem.mapper.CommentsMapper;
import com.hailin.blogsystem.service.CommentsService;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class CommentsServiceTests {

    @Autowired
    private CommentsService commentsService;

    @Autowired
    private CommentsMapper commentsMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void deletingParentCommentSoftDeletesAllDescendants() {
        ArticleComments parent = comment(1001L, null, null);
        ArticleComments child = comment(1002L, 1001L, 1001L);
        ArticleComments grandchild = comment(1003L, 1001L, 1002L);
        commentsMapper.insert(parent);
        commentsMapper.insert(child);
        commentsMapper.insert(grandchild);

        UserContext.set(101L);

        commentsService.deleteComment(1001L);

        assertSoftDeleted(1001L, 101L);
        assertSoftDeleted(1002L, 101L);
        assertSoftDeleted(1003L, 101L);
    }

    @Test
    void postingMainCommentInitializesLikeCount() {
        UserContext.set(101L);

        commentsService.postComment(1L, commentDTO("new main comment", null), "127.0.0.1", null);

        ArticleComments saved = findByContent("new main comment");
        assertThat(saved.getParentId()).isNull();
        assertThat(saved.getRootId()).isNull();
        Long storedLikeCount = jdbcTemplate.queryForObject(
                "select like_count from article_comments where id = ?",
                Long.class,
                saved.getId()
        );
        assertThat(storedLikeCount).isZero();
        assertThat(saved.getIp()).isEqualTo("127.0.0.1");
        assertThat(saved.getIpLocation()).isEqualTo("本地网络");
    }

    @Test
    void replyingToMainCommentUsesMainCommentAsRoot() {
        ArticleComments parent = comment(1101L, null, null);
        commentsMapper.insert(parent);
        UserContext.set(101L);

        commentsService.postComment(1L, commentDTO("reply to main", 1101L), "127.0.0.1", null);

        ArticleComments saved = findByContent("reply to main");
        assertThat(saved.getParentId()).isEqualTo(1101L);
        assertThat(saved.getRootId()).isEqualTo(1101L);
    }

    @Test
    void replyingToReplyKeepsExistingRoot() {
        ArticleComments root = comment(1201L, null, null);
        ArticleComments parentReply = comment(1202L, 1201L, 1201L);
        commentsMapper.insert(root);
        commentsMapper.insert(parentReply);
        UserContext.set(101L);

        commentsService.postComment(1L, commentDTO("reply to reply", 1202L), "127.0.0.1", null);

        ArticleComments saved = findByContent("reply to reply");
        assertThat(saved.getParentId()).isEqualTo(1202L);
        assertThat(saved.getRootId()).isEqualTo(1201L);
    }

    @Test
    void rejectsMissingParentComment() {
        UserContext.set(101L);

        assertThatThrownBy(() -> commentsService.postComment(
                1L,
                commentDTO("reply to missing parent", 9999L),
                "127.0.0.1",
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("父评论不存在");
    }

    @Test
    void rejectsParentCommentFromAnotherArticle() {
        ArticleComments parent = comment(1301L, null, null);
        parent.setArticleId(2L);
        commentsMapper.insert(parent);
        UserContext.set(101L);

        assertThatThrownBy(() -> commentsService.postComment(
                1L,
                commentDTO("cross article reply", 1301L),
                "127.0.0.1",
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("父评论不属于当前文章");
    }

    private CommentDTO commentDTO(String content, Long parentId) {
        CommentDTO dto = new CommentDTO();
        dto.setContent(content);
        dto.setParentId(parentId);
        return dto;
    }

    private ArticleComments findByContent(String content) {
        return commentsService.lambdaQuery()
                .eq(ArticleComments::getContent, content)
                .one();
    }

    private ArticleComments comment(Long id, Long rootId, Long parentId) {
        ArticleComments comment = new ArticleComments();
        comment.setId(id);
        comment.setArticleId(1L);
        comment.setUserId(101L);
        comment.setContent("comment " + id);
        comment.setRootId(rootId);
        comment.setParentId(parentId);
        comment.setLikeCount(0L);
        comment.setCreatedAt(LocalDateTime.now());
        return comment;
    }

    private void assertSoftDeleted(Long commentId, Long deletedBy) {
        MapResult result = jdbcTemplate.queryForObject(
                "select deleted_at, deleted_by from article_comments where id = ?",
                (rs, rowNum) -> new MapResult(
                        rs.getTimestamp("deleted_at"),
                        rs.getLong("deleted_by")
                ),
                commentId
        );

        assertThat(result).isNotNull();
        assertThat(result.deletedAt()).isNotNull();
        assertThat(result.deletedBy()).isEqualTo(deletedBy);
    }

    private record MapResult(java.sql.Timestamp deletedAt, Long deletedBy) {
    }
}
