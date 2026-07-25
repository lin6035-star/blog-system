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
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
