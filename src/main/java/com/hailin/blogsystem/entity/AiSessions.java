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
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
