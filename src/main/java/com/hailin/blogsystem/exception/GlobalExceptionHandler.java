package com.hailin.blogsystem.exception;

import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.security.RateLimitExceededException;
import com.hailin.blogsystem.utils.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    //AI 长任务准入拒绝：用户并发超限 429 / 全局容量耗尽 503。
    //走 HTTP 非 2xx 的理由同限流与额度不足——前端 SSE 在 200 时只认 `data:` 行，JSON 错误体会被静默忽略。
    //⚠️ 必须与其余 handler 同处一个 advice：跨 advice 的匹配按 advice 顺序、不按异常具体度，
    //  另起一个 advice 会被下面 @ExceptionHandler(Exception.class) 兜底抢先，状态码被吞成 500。
    @ExceptionHandler(AiTaskRejectedException.class)
    public Result<Void> handleAiTaskRejected(
            AiTaskRejectedException e,
            HttpServletResponse response
    ) {
        response.setStatus(e.getReason() == AiTaskRejectedException.Reason.USER_LIMIT
                ? HttpStatus.TOO_MANY_REQUESTS.value()
                : HttpStatus.SERVICE_UNAVAILABLE.value());
        return Result.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        return Result.error(BlogConstants.ErrorCode.BAD_REQUEST, e.getMessage());
    }

    /**
     * {@code @Valid} 参数校验失败。
     *
     * <p><b>返回 HTTP 200 + code 40001</b>，与原先散落在 controller 里的手写校验（{@code Result.error(BAD_REQUEST, ...)}）
     * **完全一致**——前端按 code 判断，行为零变化。这是本轮敢动几十个写接口的前提：
     * 校验从 controller 挪到 DTO 注解上，但**对外的契约一个字没改**。
     *
     * <p>只取**第一个**字段错误：多个字段同时不合法时，与手写校验「返回第一个不满足的 if」的
     * 行为保持一致（一次提示一条，避免把一串错误糊在一起）。
     *
     * <p>⚠️ 必须与其余 handler 同处一个 advice，理由见上面 {@code AiTaskRejectedException} 那条注释。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("请求参数不合法");
        return Result.error(BlogConstants.ErrorCode.BAD_REQUEST, message);
    }

    /**
     * 路径不存在。
     *
     * <p>原先没有 handler，会一路落到 {@code Exception} 兜底 →
     * **返回 50000「服务器内部错误」**：客户端把 URL 写错了，却被告知"服务端坏了"，
     * 方向和事实都反了，排查时会被带偏。
     *
     * <p>实测触发点：访问被 {@code management.endpoints.web.exposure} 屏蔽的 actuator 端点
     * （那是**预期内**的 404，不是故障）。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNoResourceFound(NoResourceFoundException e) {
        return Result.error(BlogConstants.ErrorCode.NOT_FOUND, "接口不存在");
    }

    /**
     * 请求体不是合法 JSON / 字段类型对不上。
     *
     * <p>这本质上是**客户端参数问题**，但原先没有 handler，会一路落到 {@code Exception} 兜底 →
     * **返回 500**（服务端错误），把一个「你传错了」说成「我坏了」。
     * 现在归到 40001，与其余参数错误一致。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.error(BlogConstants.ErrorCode.BAD_REQUEST, "请求体格式不正确");
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
