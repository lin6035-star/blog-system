package com.hailin.blogsystem.exception;

import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.security.RateLimitExceededException;
import com.hailin.blogsystem.utils.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        return Result.error(e.getCode(), e.getMessage());
    }

    //限流拒绝：BusinessException 默认返回 200，限流必须显式 429 + Retry-After
    @ExceptionHandler(RateLimitExceededException.class)
    public Result<Void> handleRateLimitExceeded(
            RateLimitExceededException e,
            HttpServletResponse response
    ) {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(e.getRetryAfterSeconds()));
        return Result.error(e.getCode(), e.getMessage());
    }

    //额度不足：同样不能走默认的 200。前端聊天的 SSE 请求在 200 时只认 `data:` 前缀的行，
    //JSON 错误体会被静默忽略，流读完仍未收到 STOP → 用户看到的是「连接意外中断」，
    //完全指不到"该充值了"这个真实原因。402 与语义也正好对应
    @ExceptionHandler(InsufficientBalanceException.class)
    public Result<Void> handleInsufficientBalance(
            InsufficientBalanceException e,
            HttpServletResponse response
    ) {
        response.setStatus(HttpStatus.PAYMENT_REQUIRED.value());
        return Result.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        return Result.error(BlogConstants.ErrorCode.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e, HttpServletRequest request, HttpServletResponse response) {
        log.error("Unhandled exception: {} {}", request.getMethod(), request.getRequestURI(), e);

        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            return null;
        }

        return Result.error(BlogConstants.ErrorCode.SERVER_ERROR, "服务器内部错误");
    }
}
