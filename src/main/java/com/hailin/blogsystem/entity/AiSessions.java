package com.hailin.blogsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_sessions")
public class AiSessions {

    @TableId( type = IdType.ASSIGN_ID)
    private Long id;  //会话ID

    private Long userId;  //用户ID
    private String title;  //会话标题
    /**
     * 当前会话正在进行的 Workflow Run ID。
     *
     * 有值表示该会话处于任务模式，下一轮用户输入优先交给 Workflow 处理。
     * 当 Workflow COMPLETED / CANCELLED 后清空。
     */
    private Long activeWorkflowRunId;

    /**
     * 会话文章锚（V3.8）：本会话最后一次"真实进入文章上下文"的文章 ID（定位本体）。
     * 锚写入受控：文章域 Agent 定位成功（AGENT_RUN）/ 详情页 QA / 页面 ACTION；
     * 闲聊与纯浏览不写。lastArticleTitle 仅为快照，权威标题读锚时查库现取。
     */
    private Long lastArticleId;
    private String lastArticleTitle;
    private String lastArticleSource;
    private LocalDateTime lastArticleUpdatedAt;

    /**
     * 会话结论锚（V3.12）：本会话最近一次文章域 Agent 结论。
     *
     * 与文章锚的区别：文章锚记「聊的是哪一篇」（定位），结论锚记「上一轮说了什么」（方向）。
     * 写点受控（两类，都在结论被用户看到/接受的时刻）：
     * - FINAL_ANSWER 终态（ARTICLE_AGENT_FINAL_ANSWER）
     * - 建议卡确认成功（WORKFLOW_SUGGESTION_CONFIRMED）
     * 单锚位覆盖：只保留最近一次结论；并发用 sourceRunId 雪花条件更新。
     */
    private Long lastConclusionArticleId;
    private String lastConclusionText;
    private Long lastConclusionSourceRunId;
    private String lastConclusionSourceType;
    private LocalDateTime lastConclusionUpdatedAt;

    /**
     * 会话学习计划锚（V4.x）：本会话最近一次"真实定位到"的学习计划（定位本体）。
     *
     * 由来：分类器只接收**当前这一条消息**（不读对话历史），多轮里第二轮省略主语就断片——
     * 「帮我分析我的c++学习计划」→「我是要应对学校课程，你会怎么改」，第二句没有计划名，
     * 系统只能反问"哪个计划"。
     *
     * 与文章锚（V3.8）同构：分类器不读锚，锚由后端在"原话没点名"时兜底消费。
     * 写点受控（只有真的指向某一个计划时才写）：分类器选中（CLASSIFIER）/
     * 后端匹配唯一命中（BACKEND_MATCH）；纯查询（dashboard 看全部）不写。
     * lastLearningPlanTitle 仅为快照，权威标题读锚时查库现取。
     */
    private Long lastLearningPlanId;
    private String lastLearningPlanTitle;
    private String lastLearningPlanSource;
    private LocalDateTime lastLearningPlanUpdatedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
