package com.hailin.blogsystem;

import com.hailin.blogsystem.entity.ArticleComments;
import com.hailin.blogsystem.entity.CommentLikes;
import com.hailin.blogsystem.mapper.CommentsMapper;
import com.hailin.blogsystem.mapper.LikeCommentsMapper;
import com.hailin.blogsystem.utils.JwtUtil;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CommentsControllerTests {

    private static final long ROOT_COMMENT_ID = 2001L;
    private static final long REPLY_COMMENT_ID = 2002L;
    private static final long READER_ID = 101L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private CommentsMapper commentsMapper;

    @Autowired
    private LikeCommentsMapper likeCommentsMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUpComments() {
        commentsMapper.insert(comment(ROOT_COMMENT_ID, null, null));
        commentsMapper.insert(comment(REPLY_COMMENT_ID, ROOT_COMMENT_ID, ROOT_COMMENT_ID));

        CommentLikes like = new CommentLikes();
        like.setCommentId(REPLY_COMMENT_ID);
        like.setUserId(READER_ID);
        like.setCreatedAt(LocalDateTime.now());
        likeCommentsMapper.insert(like);
    }

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void allowsAnonymousUsersToReadReplies() throws Exception {
        mockMvc.perform(get("/api/comments/{rootId}/replies", ROOT_COMMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list[0].id").value(REPLY_COMMENT_ID));
    }

    @Test
    void marksLikedRepliesForAuthenticatedUsers() throws Exception {
        String token = jwtUtil.generateToken(READER_ID);

        mockMvc.perform(get("/api/comments/{rootId}/replies", ROOT_COMMENT_ID)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].liked").value(true));
    }

    @Test
    void rejectsUnauthenticatedCommentMutations() throws Exception {
        mockMvc.perform(post("/api/comments/{commentId}/like", REPLY_COMMENT_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));

        mockMvc.perform(delete("/api/comments/{commentId}", REPLY_COMMENT_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void postingCommentUsesServerDerivedIpAndLocation() throws Exception {
        String token = jwtUtil.generateToken(READER_ID);

        mockMvc.perform(post("/api/articles/{articleId}/comments", 1L)
                        .header("Authorization", "Bearer " + token)
                        .header("CF-Connecting-IP", "127.0.0.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content": "comment with server ip",
                                  "parentId": null,
                                  "ip": "198.51.100.99",
                                  "ipLocation": "伪造属地"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        StoredIp storedIp = jdbcTemplate.queryForObject(
                "select ip, ip_location from article_comments where content = ?",
                (rs, rowNum) -> new StoredIp(rs.getString("ip"), rs.getString("ip_location")),
                "comment with server ip"
        );

        assertThat(storedIp).isEqualTo(new StoredIp("127.0.0.1", "本地网络"));
    }

    @Test
    void postingCommentUsesCloudflareCountryForPublicIpv6() throws Exception {
        String token = jwtUtil.generateToken(READER_ID);

        mockMvc.perform(post("/api/articles/{articleId}/comments", 1L)
                        .header("Authorization", "Bearer " + token)
                        .header("CF-Connecting-IP", "2001:4860:4860::8888")
                        .header("CF-IPCountry", "US")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content": "comment with ipv6 country",
                                  "parentId": null
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        StoredIp storedIp = jdbcTemplate.queryForObject(
                "select ip, ip_location from article_comments where content = ?",
                (rs, rowNum) -> new StoredIp(rs.getString("ip"), rs.getString("ip_location")),
                "comment with ipv6 country"
        );

        assertThat(storedIp).isEqualTo(new StoredIp("2001:4860:4860::8888", "美国"));
    }

    private ArticleComments comment(Long id, Long rootId, Long parentId) {
        ArticleComments comment = new ArticleComments();
        comment.setId(id);
        comment.setArticleId(1L);
        comment.setUserId(100L);
        comment.setContent("comment " + id);
        comment.setRootId(rootId);
        comment.setParentId(parentId);
        comment.setLikeCount(0L);
        comment.setCreatedAt(LocalDateTime.now());
        return comment;
    }

    private record StoredIp(String ip, String ipLocation) {
    }
}
