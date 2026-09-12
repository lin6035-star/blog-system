package com.hailin.blogsystem.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Data
@ConfigurationProperties(prefix = "blog.ai")
public class BlogAiProperties {
    private String systemPrompt;
    private String projectKey = "global";
    private Memory memory = new Memory();
    private Rag rag = new Rag();
    private Agent agent = new Agent();
    private RateLimit rateLimit = new RateLimit();
    private Inspection inspection = new Inspection();

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
    public static class Rag {
        private int topK = 3;
        private double similarityThreshold = 0.65;
        private Async async = new Async();
        private Rebuild rebuild = new Rebuild();
        private Rerank rerank = new Rerank();
    }

    @Data
    public static class Rerank {
        private boolean enabled = true;
        private String model = "gte-rerank-v2";
        private String workspaceId;
        private int topN = 5;
        private int maxDocuments = 30;
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

    @Data
    public static class Agent{
        private double autoStartConfidenceThreshold = 0.7;
        private int autoStartLimitPerSession = 2;
        private List<String> learningToolWhitelist = List.of("getLearningDashboard");
        private Dashboard dashboard = new Dashboard();
    }
    @Data
    public static class Dashboard{
        private int titleMaxLength = 30;
        private int taskTitleMaxLength = 20;
        private int memoryMaxLength = 80;
        private int memoryLimit = 3;
        private int hintLimit = 3;
    }

    @Data
    public static class RateLimit {
        private boolean enabled = true;
        private boolean failOpen = false;
        private Limit chat = new Limit(10, 60);
        private Limit workflow = new Limit(3, 600);
        private Limit rag = new Limit(30, 60);
    }

    @Data
    public static class Limit {
        private int limit;
        private long windowSeconds;

        public Limit() {
        }

        public Limit(int limit, long windowSeconds) {
            this.limit = limit;
            this.windowSeconds = windowSeconds;
        }
    }

    /**
     * V4 第一刀：开发者只读面板（run / step 完整 inspection）的访问控制。
     *
     * 项目无角色体系，沿用 {@link Rag.Rebuild} 的范式：开关 + userId 白名单。
     * 数据范围仍限「当前用户自己的 run」（Service 层归属校验不动）。
     */
    @Data
    public static class Inspection {
        private boolean enabled = true;
        private List<Long> allowedUserIds = List.of();
    }

}
