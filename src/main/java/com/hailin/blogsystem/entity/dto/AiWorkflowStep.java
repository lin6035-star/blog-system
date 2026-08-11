package com.hailin.blogsystem.entity.dto;
//当前执行到哪个步骤
public enum AiWorkflowStep {
    REQUIREMENT_ANALYZE(1),  // requirement_analyze 解析用户原始写作需求
    MEMORY_RETRIEVE(2),  //memory_retrieve 读取用户长期记忆中的写作偏好
    RAG_SEARCH(3),  // rag_search 检索站内相关文章知识
    GENERATE_OUTLINE(4),  //generate_outline 生成文章大纲
    GENERATE_DRAFT(5),  //generate_draft 生成文章正文草稿
    QUALITY_CHECK(6),  //quality_check 检查草稿质量
    FILL_ARTICLE(7);  // fill_article 生成 editorAction，通知前端填充编辑器

    private final int order;

    AiWorkflowStep(int order){
        this.order = order;
    }

    public int getOrder(){
        return order;
    }
}