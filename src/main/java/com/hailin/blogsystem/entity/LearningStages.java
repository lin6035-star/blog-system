package com.hailin.blogsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
