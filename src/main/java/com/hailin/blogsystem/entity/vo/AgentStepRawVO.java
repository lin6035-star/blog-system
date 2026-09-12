package com.hailin.blogsystem.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent Run 步骤·开发者原始视图（V4 第一刀，只读）。
 *
 * 与 {@link AgentStepVO} 的分工：
 * - `AgentStepVO` 是**用户侧安全摘要**，刻意不返回 `inputJson`（决策输入可能含内部信息），
 *   `message` / `summary` 均为服务端从原始列派生的展示文案；
 * - 本 VO 是**开发者审计视图**，直接给出 `inputJson` / `outputJson` 原始列，不做派生。
 *
 * 两条通道分离建模：放宽用户侧接口会污染普通用户路径，故另开端点而非加字段。
 */
@Data
public class AgentStepRawVO {
    private Integer stepNo;
    private String actionType;
    private String status;
    private String errorMessage;
    private Long durationMs;
    /** 决策输入原始 JSON（用户侧不返回）。 */
    private String inputJson;
    /** 动作结果原始 JSON（含 summary / verifier 拒绝原因等）。 */
    private String outputJson;
    private String thoughtSummary;
    private LocalDateTime createdAt;
}
