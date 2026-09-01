package com.hailin.blogsystem.entity.dto;

/**
 * Agent Run 状态。
 *
 * - RUNNING：循环执行中
 * - COMPLETED：正常结束（FINAL_ANSWER 或 maxSteps 收尾）
 * - FAILED：非法动作 / LLM 决策无法修复 / 严重异常
 * - CANCELLED：取消（V1 预留，同步 Loop 暂不出现；V2.1 用于过期建议清理）
 * - WAITING_USER：ASK_USER 终态（V1 不恢复，用户下句话开新请求）
 * - WAITING_WORKFLOW_CONFIRM：SUGGEST_WORKFLOW 终态（V2.1，等待用户确认，确认后才启动 Workflow）
 * - WAITING_WRITE_CONFIRM：SUGGEST_WRITE 终态（V2.4，等待用户确认，确认后才执行写动作）
 * - CONFIRMING：confirm 进行中的短转移态（V2.1，仅后端内部，前端不可见）
 */
public enum AiAgentRunStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    WAITING_USER,
    WAITING_WORKFLOW_CONFIRM,
    WAITING_WRITE_CONFIRM,
    CONFIRMING
}
