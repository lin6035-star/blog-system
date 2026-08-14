package com.hailin.blogsystem.entity.dto;
// 现在卡在哪种状态
public enum AiWorkflowStatus {
    RUNNING,  // running 正在执行某个步骤
    WAITING_REQUIREMENT_CONFIRM,  // waiting_requirement_confirm 写作主题或要求不明确，等待用户补充
    WAITING_OUTLINE_CONFIRM,  // waiting_outline_confirm 大纲已生成，等待用户确认或修改
    WAITING_DRAFT_CONFIRM,  // waiting_draft_confirm 正文草稿已生成，等待用户确认或修改
    WAITING_PLAN_CONFIRM,  // waiting_plan_confirm 优化方案已生成，等待用户确认或修改
    WAITING_FILL_CONFIRM,  // waiting_fill_confirm 草稿已确认，等待用户确认是否填充编辑器
    WAITING_USER_SAVE,  // waiting_user_save 已填充编辑器，等待用户自己保存草稿或发布
    PAUSED,  // paused 流程被暂停，不属于失败
    COMPLETED,  //completed 流程已完成
    FAILED,  // failed 流程执行失败
    CANCELLED  // canceled 流程被用户取消
}
