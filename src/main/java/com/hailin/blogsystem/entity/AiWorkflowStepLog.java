package com.hailin.blogsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_workflow_step_logs")
public class AiWorkflowStepLog {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long workflowRunId;
    private String logType;  //日志类型：OPERATION=操作级（确认/反馈/重试）/ STEP=步骤级（runStep 每步）
    private String step;  //步骤名称，例如 REQUIREMENT_ANALYZE/RAG_SEARCH/GENERATE_DRAFT'
    private Integer stepOrder;  //步骤顺序
    private String status;  //'RUNNING/SUCCESS/FAILED/SKIPPED'
    private Integer retryCount;  //当前步骤重试次数，首次执行为0
    private String inputSummary;  //输入摘要
    private String outputSummary;  //输出摘要
    private String errorMessage;  //错误信息
    private String metadataJson;  //扩展信息，例如模型、参数、工具信息
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Long durationMs;  //耗时毫秒
    private Integer inputTokens;
    private Integer outputTokens;
    private LocalDateTime createdAt;

}
