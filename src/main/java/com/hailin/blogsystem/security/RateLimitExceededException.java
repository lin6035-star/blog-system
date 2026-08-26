package com.hailin.blogsystem.security;

import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.exception.BusinessException;

/**
 * 限流拒绝：超限或限流服务不可用（fail-open=false）。
 * 由 GlobalExceptionHandler 单独处理为 HTTP 429 + Retry-After。
 */
public class RateLimitExceededException extends BusinessException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds) {
        super(BlogConstants.ErrorCode.RATE_LIMITED, "请求过于频繁，请稍后再试");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
