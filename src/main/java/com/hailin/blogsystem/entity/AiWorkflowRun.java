package com.hailin.blogsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI Workflow 运行实例。
 *
 * 一条记录代表一次具体的工作流执行，例如：
 * 用户发起“帮我写一篇 Redis 博客”后，系统创建一个 CREATE_ARTICLE workflow run。
 *
 * 它和普通 AI 会话不同：
 * - AI 会话保存聊天消息。
 * - WorkflowRun 保存任务状态、当前步骤、上下文、用户反馈和失败信息。
 */

@Data
@TableName("ai_workflow_runs")
public class AiWorkflowRun {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;
    /**
     * 关联的 AI 会话 ID。
     * 可为空。用于把 Workflow 和某次聊天上下文关联起来，方便刷新后恢复。
     */
    private Long conversationId;
    /**
     * Workflow 类型。
     * 第一版固定为 CREATE_ARTICLE，表示文章创作工作流。
     */
    private String workflowType;
    /**
     * Workflow 版本。
     * 第一版为 1.0。后续流程变化时，例如加入 SEO、图片生成，可以用版本区分旧任务。
     */
    private String workflowVersion;
    /**
     * 当前 Workflow 状态。
     * 例如 RUNNING、WAITING_OUTLINE_CONFIRM、WAITING_DRAFT_CONFIRM、FAILED。
     *      running,waiting_outline_confirm,waiting_draft_confirm,failed
     */
    private String status;
    /**
     * 当前执行到哪一个步骤。
     * 例如 REQUIREMENT_ANALYZE、RAG_SEARCH、GENERATE_OUTLINE、GENERATE_DRAFT。
     *      requirement_analyze,rag-search,generate_outline,generate_draft
     */
    private String currentStep;
    /**
     * Workflow 上下文 JSON。
     * 保存需求解析结果、Memory 内容、RAG references、大纲、草稿、质量检查结果、用户反馈历史等。
     */
    private String contextJson;
    /**
     * 当前 Workflow 已重试次数。
     * 第一版可以先预留，后续 LLM 超时、ES 异常、Tool 执行失败时用于自动重试控制。
     */
    private Integer retryCount;
    /**
     * 输入 token 数。
     * 第一版如果模型 SDK 没稳定返回 usage，可以先保持 0。
     */
    private Integer inputTokens;

    private Integer outputTokens;
    /**
     * 总 token 数。
     * 通常等于 inputTokens + outputTokens，用于后续成本统计。
     */
    private Integer totalTokens;
    /**
     * 暂停原因。
     * 当 status = PAUSED 时记录原因，例如用户主动暂停、等待外部资源等。
     */
    private String pauseReason;
    /**
     * 失败原因。
     * 当 status = FAILED 时记录具体错误信息，方便前端展示和后端排查。
     */
    private String errorMessage;

    private LocalDateTime createdAt;
    /**
     * 最近更新时间。
     * 每次状态推进、上下文更新、用户反馈、失败重试时都应该更新。
     */
    private LocalDateTime updatedAt;
}