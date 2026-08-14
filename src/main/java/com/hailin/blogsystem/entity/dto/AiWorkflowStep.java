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
    CONTENT_CHECK(12);  // content_check 检查重写后的内容质量

    private final int order;

    AiWorkflowStep(int order){
        this.order = order;
    }

    public int getOrder(){
        return order;
    }
}