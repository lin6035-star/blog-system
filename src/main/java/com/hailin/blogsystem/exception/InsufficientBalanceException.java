package com.hailin.blogsystem.exception;

import com.hailin.blogsystem.constants.BlogConstants;

/**
 * 额度不足。
 *
 * <p>⚠️ 虽然继承 {@link BusinessException}，但它<b>不能</b>走 BusinessException 默认的
 * 「HTTP 200 + Result JSON」响应：前端聊天的 SSE 请求在 200 时只认 `data:` 前缀的行，
 * JSON 错误体会被静默忽略，流读完仍未收到 STOP，用户看到的是「连接意外中断」——
 * 完全指不到根因。所以 {@code GlobalExceptionHandler} 给它单开一个 handler 返回 HTTP 402。
 */
public class InsufficientBalanceException extends BusinessException {

    public InsufficientBalanceException(long balance) {
        super(BlogConstants.ErrorCode.INSUFFICIENT_BALANCE,
                "额度不足（当前 " + balance + "），请先充值后再使用 AI 助手");
    }
}
