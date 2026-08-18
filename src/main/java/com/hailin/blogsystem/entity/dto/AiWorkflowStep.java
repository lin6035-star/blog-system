package com.hailin.blogsystem.entity.dto;
//当前执行到哪个步骤
public enum AiWorkflowStep {
    REQUIREMENT_ANALYZE(1),  // requirement_analyze 解析用户原始写作需求
    MEMORY_RETRIEVE(2),  //memory_retrieve 读取用户长期记忆中的写作偏好
    RAG_SEARCH(3),  // rag_search 检索站内相关文章知识
    GENERATE_OUTLINE(4),  //generate_outline 生成文章大纲
    GENERATE_DRAFT(5),  //generate_draft 生成文章正文草稿
    QUALITY_CHECK(6),  //quality_check 检查草稿质量
    FILL_ARTICLE(7),  // fill_article 生成 editorAction，通知前端填充编辑器

    LOAD_ARTICLE(8),  // load_article 加载待优化文章
    ANALYZE_ARTICLE(9),  // analyze_article 分析文章现状与问题
    GENERATE_OPTIMIZATION_PLAN(10),  // generate_optimization_plan 生成优化方案
    REWRITE_ARTICLE(11),  // rewrite_article 按优化方案重写文章
    CONTENT_CHECK(12),  // content_check 检查重写后的内容质量

    ANALYZE_GOAL(13),  // analyze_goal 分析学习目标，信息不足则追问
    GENERATE_PLAN(14), // generate_plan 生成结构化学习计划
    SAVE_PLAN(15),     // save_plan 幂等保存学习计划（upsert）

    LOAD_PLAN(16),     // load_plan 加载用户已有学习计划（含任务进度）
    ANALYZE_CHANGE(17), // analyze_change 分析调整诉求，信息不足则追问

    LOCATE_STAGE(18),  // locate_stage 定位难点所在阶段，信息不足则追问
    GENERATE_TASKS(19), // generate_tasks 拆解难点为新增任务点
    APPEND_TASKS(20);   // append_tasks 追加任务点到对应阶段

    private final int order;

    AiWorkflowStep(int order){
        this.order = order;
    }

    public int getOrder(){
        return order;
    }
}