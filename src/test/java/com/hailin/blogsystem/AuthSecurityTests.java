package com.hailin.blogsystem;

import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.entity.Users;
import com.hailin.blogsystem.entity.dto.RegisterDTO;
import com.hailin.blogsystem.service.LoginService;
import com.hailin.blogsystem.utils.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录失败限制 + 登出黑名单的端到端验证（真 Redis + 真 MySQL）。
 *
 * **为什么必须要这一层**：单元测试（`LoginAttemptLimiterTests` / `TokenBlacklistTests`）
 * 证明的是"组件自己没写错"，证明不了"它被接上了"。漏注册拦截器、Service 忘了调 limiter
 * 这类接线错误，只有走完整链路才看得见——而它们恰恰是这一刀最容易出的问题。
 *
 * 用例覆盖四条：失败累计到上限被拒 / 大小写变体共享计数 / 成功登录清零 / 登出后 token 立即失效。
 * 用的用户名统一带 {@link #PROBE_PREFIX} 前缀，跑完即删，不依赖也不污染库里的既有数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthSecurityTests {

    private static final String PROBE_PREFIX = "login_probe_";
    private static final String PROBE_PASSWORD = "Probe-Pass-123";
    /** 与 LoginAttemptLimiter 的阈值一致；改那边的话这里会红，是刻意的 */
    private static final int MAX_FAILURES = 5;
    private static final int MAX_IP_FAILURES = 30;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private LoginService loginService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private String probeUsername;

    @BeforeEach
    void setUp() {
        // 每次唯一的用户名：所有用例共用 127.0.0.1（MockMvc 的 remoteAddr），
        // 用户名不唯一的话用例之间会通过账号维度互相累计失败次数。
        // IP 维度是共享的，但单个用例的失败次数（最多 9 次）远达不到它的宽阈值，不会串味
        probeUsername = PROBE_PREFIX + System.nanoTime();
    }

    @AfterEach
    void cleanUp() {
        cleanByPattern(RedisConstants.LOGIN_FAIL_ACCOUNT_KEY_PREFIX + "*");
        cleanByPattern(RedisConstants.LOGIN_FAIL_IP_KEY_PREFIX + "*");
        cleanByPattern(RedisConstants.TOKEN_BLACKLIST_KEY_PREFIX + "*");

        List<Users> leftovers = loginService.lambdaQuery()
                .likeRight(Users::getUsername, PROBE_PREFIX)
                .list();
        if (!leftovers.isEmpty()) {
            loginService.removeByIds(leftovers.stream().map(Users::getId).toList());
        }
    }

    @Test
    void sixthFailedLoginIsRejectedWithTooManyRequests() throws Exception {
        // 用不存在的账号：失败计数对它同样生效，这本身也是"不可区分"的一部分
        for (int i = 0; i < MAX_FAILURES; i++) {
            login(probeUsername, "wrong-password")
                    .andExpect(jsonPath("$.code").value(BlogConstants.ErrorCode.LOGIN_FAILED));
        }

        login(probeUsername, "wrong-password")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value(BlogConstants.ErrorCode.RATE_LIMITED));
    }

    /**
     * 大写 / 首尾空格变体必须共用同一个计数键。
     * DB 的 collation 是 utf8mb4_unicode_ci，`LOGIN_PROBE_X` 与 `login_probe_x` 在库里是同一个用户；
     * 计数键若不归一化，攻击者用变体就能把 5 次阈值拆成 15 次。
     */
    @Test
    void caseAndSpaceVariantsShareOneCounter() throws Exception {
        for (int i = 0; i < 3; i++) {
            login(probeUsername.toUpperCase(), "wrong-password")
                    .andExpect(jsonPath("$.code").value(BlogConstants.ErrorCode.LOGIN_FAILED));
        }
        for (int i = 0; i < 2; i++) {
            login("  " + probeUsername + "  ", "wrong-password")
                    .andExpect(jsonPath("$.code").value(BlogConstants.ErrorCode.LOGIN_FAILED));
        }

        // 3 + 2 = 5，第 6 次（再换回大写）就该被拒。没归一化的话这里只会是普通的"密码错误"
        login(probeUsername.toUpperCase(), "wrong-password")
                .andExpect(status().isTooManyRequests());
    }

    /**
     * **撞库形态**：多个不同账号、各失败一两次、来自同一个来源。
     * 账号维度攒不到阈值，只有 IP 维度看得见——这条锁的就是"IP 维度真的接上了"。
     *
     * 预置 IP 计数到阈值，而不是真发 30 次请求（每次都要跑一遍 bcrypt）。
     */
    @Test
    void ipDimensionBlocksCredentialStuffingAcrossAccounts() throws Exception {
        // 先失败一次，拿到本次请求实际用的 IP 键——不硬编码 127.0.0.1
        login(probeUsername, "wrong-password")
                .andExpect(jsonPath("$.code").value(BlogConstants.ErrorCode.LOGIN_FAILED));

        Set<String> ipKeys = stringRedisTemplate.keys(RedisConstants.LOGIN_FAIL_IP_KEY_PREFIX + "*");
        assertThat(ipKeys).hasSize(1);
        String ipKey = ipKeys.iterator().next();

        stringRedisTemplate.opsForValue()
                .set(ipKey, String.valueOf(MAX_IP_FAILURES), Duration.ofMinutes(15));

        // 换一个从没失败过的账号，仍然被拒——拒的是 IP 维度，不是账号维度
        login(PROBE_PREFIX + "stuffing_" + System.nanoTime(), "wrong-password")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(BlogConstants.ErrorCode.RATE_LIMITED));
    }

    /** 账号不存在与密码错误必须完全不可区分：响应体逐字节相同，计数一样累计 */
    @Test
    void nonexistentAccountIsIndistinguishableFromWrongPassword() throws Exception {
        registerProbeUser();

        String ghostResponse = login(PROBE_PREFIX + "ghost_" + System.nanoTime(), "wrong-password")
                .andReturn().getResponse().getContentAsString();
        String realResponse = login(probeUsername, "wrong-password")
                .andReturn().getResponse().getContentAsString();

        assertThat(ghostResponse).isEqualTo(realResponse);
    }

    @Test
    void successfulLoginClearsFailureCounter() throws Exception {
        registerProbeUser();

        for (int i = 0; i < 4; i++) {
            login(probeUsername, "wrong-password")
                    .andExpect(jsonPath("$.code").value(BlogConstants.ErrorCode.LOGIN_FAILED));
        }

        login(probeUsername, PROBE_PASSWORD)
                .andExpect(jsonPath("$.code").value(BlogConstants.ErrorCode.SUCCESS));

        // 清零后要重新累计满 5 次才会被锁。没清零的话计数还停在 4，
        // 下面循环的第 2 次就会直接 429，在第一个断言处失败
        for (int i = 0; i < MAX_FAILURES; i++) {
            login(probeUsername, "wrong-password")
                    .andExpect(jsonPath("$.code").value(BlogConstants.ErrorCode.LOGIN_FAILED));
        }
        login(probeUsername, "wrong-password")
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void logoutRevokesTokenImmediately() throws Exception {
        String token = jwtUtil.generateToken(100L);

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(BlogConstants.ErrorCode.SUCCESS));

        // 同一个 token 立刻失效——这是 JWT 无状态唯一能补上的一环
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    /** 被拉黑的 token 走公开接口时按游客处理，不能因为查黑名单就报错 */
    @Test
    void revokedTokenStillReachesPublicEndpoints() throws Exception {
        String token = jwtUtil.generateToken(100L);

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(BlogConstants.ErrorCode.SUCCESS));

        mockMvc.perform(get("/api/users/100").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    /** 登出是幂等的：没带 token、token 是垃圾、重复登出都返回成功 */
    @Test
    void logoutIsIdempotentWithoutValidToken() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(jsonPath("$.code").value(BlogConstants.ErrorCode.SUCCESS));

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer not-a-real-token"))
                .andExpect(jsonPath("$.code").value(BlogConstants.ErrorCode.SUCCESS));

        String token = jwtUtil.generateToken(100L);
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                    .andExpect(jsonPath("$.code").value(BlogConstants.ErrorCode.SUCCESS));
        }
    }

    private ResultActions login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
    }

    private void registerProbeUser() {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername(probeUsername);
        dto.setNickname(probeUsername);
        dto.setPassword(PROBE_PASSWORD);
        dto.setConfirmPassword(PROBE_PASSWORD);
        loginService.register(dto);
    }

    private void cleanByPattern(String pattern) {
        Set<String> keys = stringRedisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }
}
