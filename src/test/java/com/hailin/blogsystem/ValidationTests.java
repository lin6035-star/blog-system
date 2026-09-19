package com.hailin.blogsystem;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @Valid} 参数校验的对外契约。
 *
 * <p><b>为什么第一断言是 HTTP 200</b>：项目约定业务错误走 `200 + code`
 * （见 docs/api-convention.md），只有限流 / 额度不足 / 准入拒绝这些
 * **SSE 场景必须改状态码**的才例外。@Valid 失败属于普通业务错误，
 * 所以**必须保持 200**——改了状态码，前端按 code 判断的逻辑会全线错位。
 *
 * <p>本轮改造把校验从 controller 里的手写 if 挪到 DTO 注解上，
 * **对外契约一个字没改**，这个测试就是锁这件事的。
 *
 * <p>只测**失败路径**：校验不通过时请求根本到不了 service，零副作用；
 * 通过路径会真的写库（注册账号），不该在单测里跑。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ValidationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void registerRejectsBlankUsername() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"","password":"123456","confirmPassword":"123456","nickname":"小明"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("用户名不能为空"));
    }

    @Test
    void registerRejectsOverlongUsername() throws Exception {
        String tooLong = "u".repeat(51);   // 列宽是 varchar(50)
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"123456","confirmPassword":"123456","nickname":"小明"}
                                """.formatted(tooLong)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("用户名最长 50 个字符"));
    }

    @Test
    void loginRejectsBlankPassword() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"someone","password":""}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("密码不能为空"));
    }

    /**
     * 请求体压根不是合法 JSON：这属于**客户端参数问题**，
     * 修复前会一路落到 Exception 兜底返回 500（「我坏了」），现在归到 40001（「你传错了」）。
     */
    @Test
    void malformedJsonIsClientErrorNotServerError() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-a-json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("请求体格式不正确"));
    }

    /**
     * 跨字段校验（两次密码不一致）依然由 controller 承担 —— @Valid 的注解作用在单个字段上，
     * 表达不了字段之间的关系。这条锁的是「改造没有顺手把它弄丢」。
     */
    @Test
    void registerRejectsMismatchedConfirmPassword() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"someone","password":"123456","confirmPassword":"654321","nickname":"小明"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("两次密码不一致！"));
    }
}
