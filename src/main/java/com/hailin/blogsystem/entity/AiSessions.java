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
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
