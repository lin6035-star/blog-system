package com.hailin.blogsystem.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private Billing billing = new Billing();

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
        // 秒杀抢购：比 chat 宽松（网络抖动、手速都会让用户多点几次），
        // 但仍能挡住脚本级频率——真正的防重在更前面（Redis 的 SISMEMBER 一人一单）
        private Limit seckill = new Limit(20, 60);
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

    /**
     * AI 计费：预扣 / 结算 / 退款。设计稿：docs/redis/钱包与秒杀计划.md §5。
     *
     * <p>钱包本体配置在 {@link BlogWalletProperties}（blog.wallet.*），这里只管 AI 侧的单价与预扣上限。
     * 计费单元是「每次用户可见的 AI 请求」，预扣取该路径的<b>可执行硬上限</b>，
     * 结算按实际用量退差额（结算必须满足 actualCredit &lt;= reservedCredit）。
     */
    @Data
    public static class Billing {
        /** 总开关。关闭时计费埋点整体跳过，不改变调用链行为 */
        private boolean enabled = true;

        /** 内部积分价：每 1000 token 折算多少 credit（结算向上取整，避免小请求被整数除法算成 0） */
        private long creditPer1kTokens = 10;

        /**
         * 普通聊天的输出上限（maxTokens），也是预扣公式的一项。
         *
         * <p>⚠️ 这是<b>计费硬上限</b>，不是"建议长度"：调小会截断长回答，
         * 调大则预扣更保守。改之前先看 {@code AiChatBillingSupport} 的预扣公式。
         */
        private int chatMaxTokens = 4000;

        /**
         * 工具调用的最大轮数。
         *
         * <p>每轮都会把完整上下文重发一遍，token 会成倍增长，所以预扣要按轮数放大。
         */
        private int maxToolRounds = 5;

        /**
         * 各 bizType 的单次预扣硬上限（credit），key = CHAT / AGENT / WORKFLOW_ACTION。
         *
         * <p>⚠️ 必须来自<b>可执行硬上限</b>——最大输入 + maxTokens + 最大工具轮数 / maxSteps。
         * <b>不能取 P95</b>：P95 只覆盖 95% 的请求，第 96 百分位以后照样透支；
         * 更关键的是结算要满足 actualCredit &lt;= reservedCredit，用 P95 会直接破坏这个不变量。
         */
        private Map<String, Long> maxReservePerBizType = new LinkedHashMap<>();

        /**
         * 预扣单的超时释放时间（分钟）。
         *
         * <p>必须大于该路径的最大全链路超时并留余量——这是<b>进程崩溃时的兜底</b>，
         * 正常路径永远走不到它。晚到的结算因状态不再是 RESERVED 必须失败，不能二次扣款。
         */
        private long reserveExpireMinutes = 30;
    }

}
