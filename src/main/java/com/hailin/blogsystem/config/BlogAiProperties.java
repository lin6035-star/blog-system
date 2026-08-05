package com.hailin.blogsystem.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Data
@ConfigurationProperties(prefix = "blog.ai")
public class BlogAiProperties {
    private String systemPrompt;
    private Memory memory = new Memory();
    private Rag rag = new Rag();

    @Data
    public static class Memory{
        private boolean enabled = true;
        private int maxMessages = 40;
        private Extraction extraction = new Extraction();
    }

    @Data
    public static class Extraction {
        private int coreSize = 1;
        private int maxSize = 2;
        private int queueCapacity = 30;
        private String threadNamePrefix = "memory-extract-";
    }

    @Data
    public static class Rag{
        private int topK = 3;
        private double similarityThreshold = 0.65;
        private Async async = new Async();
        private Rebuild rebuild = new Rebuild();
    }

    @Data
    public static class Rebuild{
        private boolean enabled = true;
        private List<Long> allowedUserIds = List.of();
    }

    @Data
    public static class Async {
        private int coreSize = 2;
        private int maxSize = 4;
        private int queueCapacity = 100;
        private String threadNamePrefix = "rag-sync-";
    }
}
