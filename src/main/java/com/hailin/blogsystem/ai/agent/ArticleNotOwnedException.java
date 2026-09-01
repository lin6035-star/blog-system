package com.hailin.blogsystem.ai.agent;

/**
 * 文章域终局失败异常（V2.5）。
 *
 * 文章不存在 / 不属于当前用户时抛出：继续循环也无法改变结果，
 * 由 AbstractAgentRuntime 的终局失败机制在失败 step 落库后直接结束 run
 * （COMPLETED + 本异常 message 作为友好文案返回用户）。
 *
 * message 必须是对用户可读的完整中文句（直接作为 finalAnswer 展示）。
 */
public class ArticleNotOwnedException extends RuntimeException {

    public ArticleNotOwnedException(String message) {
        super(message);
    }
}
