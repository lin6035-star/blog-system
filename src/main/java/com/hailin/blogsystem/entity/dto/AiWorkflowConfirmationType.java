package com.hailin.blogsystem.entity.dto;

/**
 * 确认面板类型：Workflow 停在等待确认态时，前端按该类型渲染确认面板。
 * 与 AiWorkflowStatus 解耦——状态是流程语义，type 是"要老板确认的内容是什么"。
 */
public enum AiWorkflowConfirmationType {
    REQUIREMENT,    //追问补充信息（面板只输入，无同意/不同意）
    OUTLINE,        //文章大纲（文本）
    DRAFT,          //文章草稿 / 优化稿
    PLAN,           //文章优化方案（文本）
    LEARNING_PLAN,  //学习计划（结构化阶段/任务）
    FILL            //填充确认（无展示内容，仅确认是否填充编辑器）
}
