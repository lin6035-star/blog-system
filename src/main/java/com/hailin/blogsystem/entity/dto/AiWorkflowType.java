package com.hailin.blogsystem.entity.dto;

public enum AiWorkflowType {
    CREATE_ARTICLE,  //文章创作工作流
    OPTIMIZE_ARTICLE, //文章优化工作流
    LEARNING_PLAN,  //学习规划工作流
    LEARNING_PROGRESS,  //学习进度工作流（在已有学习计划上调整）
    LEARNING_ASSIST  //学习难点攻坚工作流（拆解难点，追加任务点到对应阶段）
}