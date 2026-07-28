package com.hailin.blogsystem.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "blog.ai")
public class BlogAiProperties {
    private String systemPrompt;
    private Memory memory = new Memory();

    @Data
    public static class Memory{
        private boolean enabled = true;
        private int maxMessages = 30;
    }
}
