package com.hailin.blogsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("ai_user_memories")
public class AiUserMemories {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;
    private String memoryType;  //记忆类型：PROFILE / PREFERENCE / PROJECT_STATE
    private String memoryKey;  //记忆键，用于同类记忆去重和更新
    private String content;  //自然语言记忆内容
    private String source;  //来源：USER_EXPLICIT / USER_CONFIRMED / SYSTEM_SUMMARY
    private BigDecimal confidence;  //可信度 0-1
    private Integer importance;  //重要性 1-10
    private Integer enabled;  //是否启用 1启用，0停用
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
