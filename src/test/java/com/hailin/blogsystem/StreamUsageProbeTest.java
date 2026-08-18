package com.hailin.blogsystem;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 临时探针：验证 DashScope 流式 + streamUsage(true) 能否拿到精确 usage。
 * 跑完即删。结果看控制台 [PROBE] 日志。
 */
@Slf4j
@SpringBootTest
public class StreamUsageProbeTest {

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Test
    void probeStreamUsage() {
        StringBuilder content = new StringBuilder();
        final Usage[] lastUsage = new Usage[1];

        chatClientBuilder.build()
                .prompt()
                .options(OpenAiChatOptions.builder()
                        .streamUsage(true)
                        .build())
                .user("hi，用一句话介绍 Redis")
                .stream()
                .chatResponse()
                .doOnNext(response -> {
                    String text = response.getResult() == null ? "" : response.getResult().getOutput().getText();
                    if (text != null) {
                        content.append(text);
                    }
                    Usage usage = response.getMetadata().getUsage();
                    if (usage != null) {
                        lastUsage[0] = usage;
                        log.info("[PROBE] chunk 携带 usage: {}", usage);
                    }
                })
                .blockLast();

        log.info("[PROBE] 流式内容长度: {}", content.length());
        if (lastUsage[0] != null) {
            log.info("[PROBE] ✅ 拿到精确 usage: promptTokens={}, completionTokens={}, totalTokens={}",
                    lastUsage[0].getPromptTokens(), lastUsage[0].getCompletionTokens(), lastUsage[0].getTotalTokens());
        } else {
            log.info("[PROBE] ❌ 流式结束没有拿到 usage（DashScope 未返回或 Spring AI 未解析）");
        }

        //基础断言：流式本身要正常（内容非空），usage 有无以日志为准
        assertThat(content.toString()).isNotBlank();
    }
}
