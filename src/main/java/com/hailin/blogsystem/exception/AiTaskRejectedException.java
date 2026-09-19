package com.hailin.blogsystem.exception;

import com.hailin.blogsystem.constants.BlogConstants;
import lombok.Getter;

/**
 * AI 长任务准入拒绝。
 *
 * <p>⚠️ 虽然继承 {@link BusinessException}，但它<b>不能</b>走默认的「HTTP 200 + Result JSON」：
 * 前端聊天的 SSE 请求在 200 时只认 {@code data:} 前缀的行，JSON 错误体会被静默忽略，
 * 用户看到的是「连接意外中断」——完全指不到根因（与 {@code InsufficientBalanceException} 同一个坑）。
 * 所以 {@code GlobalExceptionHandler} 按 reason 分别返回 429 / 503。
 *
 * <p><b>必须加在同一个 {@code GlobalExceptionHandler} 里</b>，不能另起一个 advice：
 * 跨 advice 的匹配按 advice 顺序、不按异常具体度，会被 {@code @ExceptionHandler(Exception.class)}
 * 抢先把状态码吞成 500（实测结论见 {@code SseAdmissionProbeTests}）。
 */
@Getter
public class AiTaskRejectedException extends BusinessException {

    /** 拒绝原因。对外只有这两个：内部再细分的原因不暴露给前端。 */
    public enum Reason {

        /** 该用户已有过多进行中的长任务。 */
        USER_LIMIT(
                BlogConstants.ErrorCode.AI_TASK_USER_LIMIT,
                "你还有任务正在处理中，等它跑完再试"
        ),

        /** 全局 worker + queue 容量耗尽。 */
        POOL_FULL(
                BlogConstants.ErrorCode.AI_TASK_POOL_FULL,
                "服务当前繁忙，请稍后再试"
        );

        private final int code;
        private final String message;

        Reason(int code, String message) {
            this.code = code;
            this.message = message;
        }

        public int getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }
    }

    private final Reason reason;

    public AiTaskRejectedException(Reason reason) {
        super(reason.getCode(), reason.getMessage());
        this.reason = reason;
    }
}
