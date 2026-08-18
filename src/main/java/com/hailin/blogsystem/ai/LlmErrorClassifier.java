package com.hailin.blogsystem.ai;

/**
 * LLM 调用异常 → 面向用户的友好分类文案。
 * 基于异常消息字符串匹配，覆盖常见网络 / 额度密钥 / 服务端问题；原始堆栈由后端日志保留（排障不丢）。
 */
public final class LlmErrorClassifier {

    private LlmErrorClassifier() {
    }

    public static String friendlyMessage(Throwable e) {
        String msg = String.valueOf(e.getMessage());

        //超时类：Flux.timeout 抛 java.util.concurrent.TimeoutException（异常链遍历）
        if (containsTimeoutException(e)) {
            return "AI 服务响应超时，请稍后重试";
        }

        //网络类：断网 / 超时 / 流式读取中断
        if (containsAny(msg,
                "EOFException", "IOException", "chunked transfer",
                "ConnectTimeout", "connect timed out", "timed out",
                "Connection refused", "Connection reset", "reset by peer",
                "UnknownHost", "PrematureClose", "response failed with cause")) {
            return "网络连接失败，请检查网络后重试";
        }

        //额度 / 密钥类
        if (containsAny(msg,
                "401", "402",
                "InvalidApiKey", "invalid api key", "AuthenticationError",
                "quota", "insufficient", "balance", "Arrearage",
                "额度", "余额", "欠费")) {
            return "AI 接口额度不足或密钥无效，请检查 API 配置";
        }

        //服务端 / 限流类
        if (containsAny(msg,
                "429", "500", "502", "503", "504",
                "rate limit", "too many requests", "Throttling")) {
            return "AI 服务暂时繁忙，请稍后重试";
        }

        return "AI 服务调用失败，请稍后重试";
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

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
