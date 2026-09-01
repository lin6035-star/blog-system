package com.hailin.blogsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent Runtime 运行实例。
 *
 * 一条记录代表一次 Learning Agent 只读 Loop 执行：
 * 用户发起“今天继续学什么 / 帮我安排今天学习”后，
 * Agent 在 maxSteps 边界内逐步决策、执行只读动作、最终给出学习建议。
 *
 * 与 ai_workflow_runs 的区别：
 * - WorkflowRun = 固定流程 + 确认门（高影响业务流）
 * - AgentRun = 有边界的动态决策循环（只读，V1 同步执行）
 */
@Data
@TableName("ai_agent_runs")
public class AiAgentRun {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    /**
     * 关联的 AI 会话 ID。用于把 Agent Run 与聊天上下文关联。
     */
    private Long sessionId;

    /**
     * 用户原始请求（目标）。
     */
    private String goal;

    /**
     * 运行状态：RUNNING / COMPLETED / FAILED / CANCELLED / WAITING_USER。
     */
    private String status;

    /**
     * 当前执行到的步号。
     */
    private Integer currentStep;

    /**
     * 最大步数边界。
     */
    private Integer maxSteps;

    /**
     * 已使用步数。
     */
    private Integer usedSteps;

    /**
     * Agent Loop 上下文 JSON：目标、已裁剪的 observation 列表、flag 等。
     * 不存完整 prompt，不存密钥。
     */
    private String contextJson;

    /**
     * 最终回答文本（COMPLETED 时落库）。
     */
    private String finalAnswer;

    /**
     * 失败原因（FAILED 时记录，供排查与前端友好文案）。
     */
    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
