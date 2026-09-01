package com.hailin.blogsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("learning_stages")
public class LearningStages {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long planId;
    private Integer orderNum;
    private String title;
    private String tasks;  //JSON 文本列，存 List<Map> 的 JSON 字符串
    /**
     * 乐观锁版本（V2.4 底座）：tasks JSON 的读改写（勾选/追加）都落在本行，
     * 并发写时靠 version CAS 防丢失更新。
     */
    @Version
    private Integer version = 0;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
