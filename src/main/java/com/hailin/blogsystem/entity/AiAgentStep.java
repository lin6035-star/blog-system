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
     * 失败原因。
     */
    private String errorMessage;

    private LocalDateTime createdAt;
}
