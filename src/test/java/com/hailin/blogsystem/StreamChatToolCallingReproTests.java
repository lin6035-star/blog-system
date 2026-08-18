package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.entity.AiPrompt;
import com.hailin.blogsystem.service.AiModelService;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 链路冒烟测试（真实 LLM）：普通聊天流式 + 工具调用（查询学习计划）。
 * 回归场景：qwen 流式 tool_calls 分片 name 缺失 → 聚合断言炸流 → 自动降级非流式重跑。
 */
@SpringBootTest
class StreamChatToolCallingReproTests {

    @Autowired
    private AiModelService aiModelService;

    @Test
    void queryPlanTriggersToolCalling() {
        //streamChat 在方法体读取 UserContext 构建 ToolContext（模拟真实请求线程的登录态）
        UserContext.set(104L);
        AiPrompt prompt = AiPrompt.builder()
                .finalPromptContext("你是博客网站的AI助手，回答用户问题。\n用户问题：我有几个学习计划")
                .userMessage("我有几个学习计划")
                .build();

        List<String> chunks = aiModelService.streamChat(
                        prompt, "repro-" + UUID.randomUUID(), new TokenUsageAccumulator())
                .collectList()
                .block();

        String full = String.join("", chunks);
        System.out.println("=== FULL REPLY ===");
        System.out.println(full);
        assertThat(full).isNotEmpty();
        assertThat(full).doesNotContain("AI 服务暂时不可用");
    }
}
