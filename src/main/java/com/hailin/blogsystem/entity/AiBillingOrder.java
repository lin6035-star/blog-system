package com.hailin.blogsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 计费单（预扣 / 结算 / 退款）。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §2④
 *
 * <p>状态机只有三态：{@code RESERVED → SETTLED | RELEASED}。
 * <b>没有 SETTLEMENT_PENDING</b>——usage 缺失时按预扣全额结算（actual = reserved）+ 告警，
 * 不为一个边缘场景引入长期挂起的生命周期。
 */
@Data
@TableName("ai_billing_order")
public class AiBillingOrder {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 业务单号；流水幂等键由「操作类型 + 它」派生，不能直接用它当流水键 */
    private String orderNo;

    private Long userId;

    /** CHAT / AGENT / WORKFLOW_ACTION */
    private String bizType;

    /** 调用前已存在的计费键（用户消息 id / workflowRunId + action + version） */
    private String bizId;

    /** 调用后回填：assistantMessageId / agentRunId / stepLogId */
    private String resourceId;

    /** RESERVED / SETTLED / RELEASED */
    private String status;

    /** 预扣额度（来自该路径的可执行硬上限） */
    private Long reservedCredit;

    /** 实际结算额度（SETTLED 时写入） */
    private Long actualCredit;

    private Integer promptTokens;
    private Integer completionTokens;

    /** V1 结算依据；prompt/completion 仅作观测 */
    private Integer totalTokens;

    private Integer version;

    /** 超时释放兜底时间——进程崩溃时才走得到 */
    private LocalDateTime expireAt;

    private LocalDateTime createdAt;
    private LocalDateTime settledAt;
}
