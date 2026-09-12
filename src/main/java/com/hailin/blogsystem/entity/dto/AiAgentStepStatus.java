package com.hailin.blogsystem.entity.dto;

/**
 * Agent Step 状态。
 *
 * - RUNNING：执行中
 * - SUCCESS：动作执行成功，observation 已落库
 * - FAILED：动作执行失败（Redis / RAG / Memory 故障等）
 * - SKIPPED：未执行（如重复查询被后端主动省略）
 */
public enum AiAgentStepStatus {
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED
}
