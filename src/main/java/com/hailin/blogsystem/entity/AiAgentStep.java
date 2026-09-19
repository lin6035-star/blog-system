package com.hailin.blogsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent Runtime 步骤记录。
 *
 * 每执行一个动作（查询计划 / 查询记忆 / 检索 RAG / 提问 / 回答）落一条，
 * 用于调试 Agent 为什么选择下一步，以及后续前端 Step 时间线（V1.3）。
 */
@Data
@TableName("ai_agent_steps")
public class AiAgentStep {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 所属 Agent Run ID。
     */
    private Long agentRunId;

    /**
     * 步号，从 1 开始。
     */
    private Integer stepNo;

    /**
     * 动作类型：QUERY_LEARNING_DASHBOARD / QUERY_MEMORY / SEARCH_RAG / ASK_USER / FINAL_ANSWER。
     */
    private String actionType;

    /**
     * V3.10：思考摘要（AgentThoughtSanitizer 清洗后，仅展示用，非决策依据）。
     * 来源 = LLM 决策 JSON 顶层 "thought"；null = 无摘要（前端回退模板文案）。
     * 不存模型原始输出——原始链不落库（审计看 actionType / input / observation / 状态）。
     */
    private String thoughtSummary;

    /**
     * 动作输入摘要 JSON（LLM 给的关键词 / planId 等）。
     */
    private String inputJson;

    /**
     * 动作输出摘要 JSON（observation）。
     */
    private String outputJson;

    /**
     * 状态：RUNNING / SUCCESS / FAILED / SKIPPED。
     */
    private String status;

    /**
     * 执行耗时毫秒。
     */
    private Long durationMs;

    /**
     * 本步决策（decide）的 LLM token 用量。
     *
     * 语义是「决定要走这一步」的成本，不是动作执行的成本——只读动作本身不调 LLM。
     * JSON 解析失败触发 repair 时，两次调用的用量都计入本步。
     */
    private Integer inputTokens;

    private Integer outputTokens;

    /**
     * 失败原因。
     */
    private String errorMessage;

    private LocalDateTime createdAt;
}
