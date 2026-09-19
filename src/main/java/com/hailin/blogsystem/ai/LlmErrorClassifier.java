package com.hailin.blogsystem.ai;

/**
 * LLM 调用异常 → 失败类型 + 面向用户的友好文案。
 *
 * <p><b>本类曾经只做文案映射</b>：它能认出 429，但那个判定只用来挑一句中文提示，
 * <b>没有参与任何重试决策</b>——于是「限流」和「参数错误」在调用方眼里毫无区别，
 * 要么全都不重试（Spring AI 的默认行为），要么全都重试（RAG 的 {@code catch(Exception)}）。
 *
 * <p>压测暴露的问题正是前者：40 并发下 30 个请求被 429 打挂、零重试、静默降级。
 * 所以这里把判定<b>提升为策略输入</b>：{@link #classify} 给出失败类型，
 * {@link #isRetryable} 回答「重试有没有意义」，退避时长由调用方按类型决定。
 *
 * <p><b>判定基于异常消息字符串匹配</b>（沿用原有做法，不解析 HTTP 响应体）——
 * 够用且不依赖具体 SDK 类型；代价是理论上可能误匹配，所以判定顺序刻意从「最具体」到「最宽泛」。
 */
public final class LlmErrorClassifier {

    private LlmErrorClassifier() {
    }

    /**
     * 失败类型。{@code retryable} 是**策略**不是建议：
     * 不可重试的类型重试只是白烧钱，调用方不该自行其是。
     */
    public enum FailureKind {

        /** 超时：可重试。 */
        TIMEOUT(true, "AI 服务响应超时，请稍后重试"),

        /** 网络层失败（断连 / DNS / 流中断）：可重试。 */
        NETWORK(true, "网络连接失败，请检查网络后重试"),

        /** 内容被供应商审核拒绝：**不可重试**——同样的内容重发多少次都一样。 */
        CONTENT_REJECTED(false, "内容未通过审核，请调整后重试"),

        /** 额度耗尽 / 密钥无效：**不可重试**——重试改变不了账户状态。 */
        QUOTA_OR_AUTH(false, "AI 接口额度不足或密钥无效，请检查 API 配置"),

        /**
         * 限流：可重试，但**退避要明显长于其他类型**。
         * 这是唯一一类「重试本身会加剧问题」的失败——供应商明确要求降速。
         */
        RATE_LIMITED(true, "AI 服务暂时繁忙，请稍后重试"),

        /** 服务端 5xx：可重试。 */
        SERVER_ERROR(true, "AI 服务暂时繁忙，请稍后重试"),

        /** 未识别：**保守判为不可重试**（宁可少重试，不要拿陌生错误反复打）。 */
        UNKNOWN(false, "AI 服务调用失败，请稍后重试");

        private final boolean retryable;
        private final String friendlyMessage;

        FailureKind(boolean retryable, String friendlyMessage) {
            this.retryable = retryable;
            this.friendlyMessage = friendlyMessage;
        }

        public boolean retryable() {
            return retryable;
        }

        public String friendlyMessage() {
            return friendlyMessage;
        }
    }

    /**
     * 归类失败类型。判定顺序＝从最具体到最宽泛，**顺序本身是逻辑的一部分**：
     * 比如额度类消息里可能同时出现 "429"，必须让它先于限流被识别。
     */
    public static FailureKind classify(Throwable e) {
        if (e == null) {
            return FailureKind.UNKNOWN;
        }
        String msg = collectMessages(e);

        // 超时先行：Flux.timeout 抛的 TimeoutException 消息里未必有关键词，只能靠类型判断
        if (containsTimeoutException(e)) {
            return FailureKind.TIMEOUT;
        }

        if (containsAny(msg,
                "EOFException", "IOException", "chunked transfer",
                "ConnectTimeout", "connect timed out", "timed out",
                "Connection refused", "Connection reset", "reset by peer",
                "UnknownHost", "PrematureClose", "response failed with cause")) {
            return FailureKind.NETWORK;
        }

        // 内容审核：必须排在 4xx 通用判断之前，否则会被当成参数错误
        if (containsAny(msg,
                "data_inspection_failed", "content_filter", "content policy",
                "content is not allowed", "inappropriate", "内容审核", "违规")) {
            return FailureKind.CONTENT_REJECTED;
        }

        // 额度 / 密钥：必须排在限流之前——这类消息里常同时出现 429
        if (containsAny(msg,
                "401", "402",
                "InvalidApiKey", "invalid api key", "AuthenticationError",
                "quota", "insufficient", "balance", "Arrearage",
                "额度", "余额", "欠费")) {
            return FailureKind.QUOTA_OR_AUTH;
        }

        // 限流单独成类（原先与 5xx 混在一起）：它决定退避要不要更长
        if (containsAny(msg,
                "429", "rate limit", "rate_limit", "too many requests",
                "Throttling", "limit_requests")) {
            return FailureKind.RATE_LIMITED;
        }

        if (containsAny(msg, "500", "502", "503", "504")) {
            return FailureKind.SERVER_ERROR;
        }

        return FailureKind.UNKNOWN;
    }

    /** 该失败重试有没有意义。 */
    public static boolean isRetryable(Throwable e) {
        return classify(e).retryable();
    }

    /** 退避封顶：8 秒。再长就不如直接失败、让用户手动重试来得干脆。 */
    private static final long MAX_BACKOFF_MS = 8000L;

    /**
     * 退避时长：指数 + 抖动。
     *
     * <p><b>限流的基数更大</b>（2s vs 1s）——它是唯一一类「重试本身会加剧问题」的失败：
     * 供应商明确要求降速时抢着重试，只会让限流窗口更长。
     *
     * <p><b>抖动是必须的</b>：压测时 40 个请求是**同步**发出、同步撞上限流的。
     * 若重试也是同步的，它们会在同一时刻再次打成脉冲，把同一个坑原样再踩一遍。
     *
     * <p>重试次数由调用方决定（同步路径用户在线等，该少；异步路径可以多），
     * 本方法只管「这一次失败该等多久」。
     */
    public static long backoffMillis(int attempt, FailureKind kind) {
        long base = kind == FailureKind.RATE_LIMITED ? 2000L : 1000L;
        long delay = Math.min(base * (1L << (attempt - 1)), MAX_BACKOFF_MS);
        long jitter = (long) (delay * (Math.random() - 0.5));   // ±50%
        return Math.max(100L, delay + jitter);
    }

    /** 失败类型对应的用户文案。空异常给通用文案。 */
    public static String friendlyMessage(Throwable e) {
        return classify(e).friendlyMessage();
    }

    //把异常包装成带步骤上下文的 RuntimeException：
    //消息已以步骤前缀开头（如"模型返回空内容"这类自己抛的友好消息）→ 原样保留；
    //底层异常（网络/额度/服务端）→ 用 friendlyMessage 分类成友好文案。
    public static RuntimeException wrap(String stepPrefix, Throwable e) {
        String message = e.getMessage();
        if (message != null && message.startsWith(stepPrefix)) {
            return (RuntimeException) e;
        }
        return new RuntimeException(stepPrefix + friendlyMessage(e), e);
    }

    public static boolean containsTimeoutException(Throwable e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 把整条异常链的 message 拼起来再匹配。
     *
     * <p><b>只看最外层 message 是不够的</b>：框架包装后的异常往往是
     * {@code RuntimeException("AI 服务调用失败", new ConnectException("Connection refused"))}——
     * 真正的错误信息在 cause 里，只看外层等于什么都没看到。
     * （本类的 {@link #containsTimeoutException} 一直是遍历 cause 链的，
     * 两种判定行为不一致本身就是个隐患，这里统一。）
     *
     * <p>深度上限 10 防异常链自引用成环——真实调用栈不会这么深。
     */
    private static String collectMessages(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable current = e;
        int depth = 0;
        while (current != null && depth++ < 10) {
            if (current.getMessage() != null) {
                sb.append(current.getMessage()).append(' ');
            }
            current = current.getCause();
        }
        return sb.toString();
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
