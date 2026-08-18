package com.hailin.blogsystem;

import com.hailin.blogsystem.utils.JwtUtil;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UsersControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void updatesCurrentUserProfile() throws Exception {
        String token = jwtUtil.generateToken(101L);

        mockMvc.perform(put("/api/users/me/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "nickname": "新的昵称",
                                  "bio": "新的个人简介"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(101L))
                .andExpect(jsonPath("$.data.nickname").value("新的昵称"))
                .andExpect(jsonPath("$.data.bio").value("新的个人简介"));
    }

    @Test
    void getsCurrentUserInfoWithFollowCounts() throws Exception {
        String token = jwtUtil.generateToken(100L);

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(100L))
                .andExpect(jsonPath("$.data.followingCount").value(1))
                .andExpect(jsonPath("$.data.followersCount").value(1));
    }

    @Test
    void getsPublicUserFollowingWithPagination() throws Exception {
        mockMvc.perform(get("/api/users/100/following")
                        .param("page", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list", hasSize(1)))
                .andExpect(jsonPath("$.data.list[0].id").value(101L))
                .andExpect(jsonPath("$.data.list[0].nickname").value("Reader Nick"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(10));
    }

    @Test
    void getsPublicUserFollowersAndExcludesSoftDeletedFollows() throws Exception {
        String authorToken = jwtUtil.generateToken(100L);

        mockMvc.perform(get("/api/users/100/followers")
                        .header("Authorization", "Bearer " + authorToken)
                        .param("page", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list", hasSize(1)))
                .andExpect(jsonPath("$.data.list[0].id").value(102L))
                .andExpect(jsonPath("$.data.list[0].nickname").value("Follower Nick"))
                .andExpect(jsonPath("$.data.list[0].followed").value(false))
                .andExpect(jsonPath("$.data.list[0].self").value(false))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void keepsMutualFollowStateAfterRefreshingFollowersList() throws Exception {
        String authorToken = jwtUtil.generateToken(100L);

        mockMvc.perform(post("/api/users/102/follow")
                        .header("Authorization", "Bearer " + authorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/users/100/followers")
                        .header("Authorization", "Bearer " + authorToken)
                        .param("page", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list", hasSize(1)))
                .andExpect(jsonPath("$.data.list[0].id").value(102L))
                .andExpect(jsonPath("$.data.list[0].followed").value(true))
                .andExpect(jsonPath("$.data.list[0].self").value(false));
    }

    @Test
    void publicUserInfoUsesActiveFollowState() throws Exception {
        String authorToken = jwtUtil.generateToken(100L);

        mockMvc.perform(get("/api/users/101")
                        .header("Authorization", "Bearer " + authorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.followed").value(true))
                .andExpect(jsonPath("$.data.self").value(false));

        mockMvc.perform(get("/api/users/100")
                        .header("Authorization", "Bearer " + authorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.followed").value(false))
                .andExpect(jsonPath("$.data.self").value(true));
    }

    @Test
    void keepsFollowStateAfterRefreshingPublicProfile() throws Exception {
        String authorToken = jwtUtil.generateToken(100L);

        mockMvc.perform(post("/api/users/102/follow")
                        .header("Authorization", "Bearer " + authorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/users/102")
                        .header("Authorization", "Bearer " + authorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.followed").value(true));

        mockMvc.perform(delete("/api/users/102/follow")
                        .header("Authorization", "Bearer " + authorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/users/102")
                        .header("Authorization", "Bearer " + authorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.followed").value(false));
    }
}
