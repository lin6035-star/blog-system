package com.hailin.blogsystem.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent Run 步骤（V2.2 inspection 层，只读）。
 *
 * message 为思考面板展示文案（与实时 AGENT_STEP 事件一致，刷新前后不串味）；
 * summary 为动作执行后的观察摘要（outputJson.summary），
 * 不返回 inputJson（决策输入可能含内部信息）。
 */
@Data
public class AgentStepVO {
    private Integer stepNo;
    private String actionType;
    private String status;
    private String errorMessage;
    private Long durationMs;
    private String message;
    private String summary;
    private LocalDateTime createdAt;
}
