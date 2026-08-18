package com.hailin.blogsystem.entity.dto;

import lombok.Data;

@Data
public class AiWorkflowLearningPlanDTO {

    private Long conversationId;  //关联的 AI 会话 ID，可为空
    private String goal;  //用户原始学习目标（原文直通，不信任 LLM 提取的结构化字段）
}
