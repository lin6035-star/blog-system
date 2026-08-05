package com.hailin.blogsystem.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * AI 模块全局配置。
 * <p>
 * 自定义 {@code openAiRestClientBuilder} Bean 会优先于 Spring AI 自动装配的默认 Bean，
 * 在请求体注入 {@code enable_thinking=false}，关闭百炼模型的深度思考模式以避免额外计费。
 * </p>
 */
@Configuration
public class AiConfig {

    /**
     * 供 Spring AI {@code OpenAiAutoConfiguration} 注入使用的 {@link RestClient.Builder}。
     * 通过请求拦截器直接修改请求体 JSON，写入 {@code "enable_thinking": false}，
     * 对百炼 DashScope 兼容端点的所有模型生效（qwen、deepseek 等均通用）。
     */
    @Bean
    RestClient.Builder openAiRestClientBuilder() {
        ObjectMapper mapper = new ObjectMapper();

        return RestClient.builder()
                .requestInterceptor((request, body, execution) -> {
                    if (body == null || body.length == 0) {
                        return execution.execute(request, body);
                    }

                    try {
                        JsonNode root = mapper.readTree(body);
                        ((ObjectNode) root).put("enable_thinking", false);
                        body = mapper.writeValueAsBytes(root);
                    } catch (Exception ignored) {
                        // 非 JSON 或解析失败，原样发送
                    }

                    return execution.execute(request, body);
                });
    }
}
