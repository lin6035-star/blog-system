package com.hailin.blogsystem.entity.dto;

import lombok.Data;

@Data
public class AiIntent {
    private String intent;  //大类，比如 general_chat / user_profile_insight / article_action
    private Double confidence;  //LLM 对当前意图判断的置信度；分类失败时由后端置为 0
    private String actionType;  //细分动作，比如 likeArticle / followAuthor
    private String articleId;  //文章ID
    private String authorId;
    private String userId;
    private String content;  //评论内容,补充文本
    private String target;
    private String param;

    private String topic;  //写作主题
    private String topicEvidence; // 用户原话中连续出现的主题片段
    private String categoryName;  //文章指定类型，默认为随笔
    private String requirements;  //用户补充要求

    private String keyWord;

    private String learningPlanRef;  //学习计划名称或关键词，只能来自用户原话
    private String learningStageRef;  //学习阶段或任务名称，只能来自用户原话

    private String suggestedAction;  // CHAT / TOOL / WORKFLOW / CTA，LLM 建议，不是最终裁决
    private String suggestedWorkflowType;// LEARNING_PLAN / LEARNING_PROGRESS / LEARNING_ASSIST
    private String risk;// LOW / MEDIUM / HIGH
    private String reason; // 一句话解释
}
