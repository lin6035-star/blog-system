package com.hailin.blogsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_messages")
public class AiMessages {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;  //消息ID

    private Long sessionId;  //会话ID
    private String workflowRunId;
    private Long agentRunId;  //关联的 Agent Run ID（V2.1 建议卡快照恢复用）
    private String role;  //角色
    private String content;  //消息内容
    private String pageContext;  //页面上下文
    private Long tokenCount;  //token消耗数
    private LocalDateTime createdAt;
}
