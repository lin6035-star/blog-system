package com.hailin.blogsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("learning_plans")
public class LearningPlans {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    @TableId( type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private String title;
    private String goal;
    private String status; //默认 "ACTIVE"，常量值：ACTIVE/COMPLETED/ARCHIVED)
    private Long sourceWorkflowRunId;  //(Long, 创建它的 run id——幂等唯一键)
    /**
     * 乐观锁版本（V2.4 底座）：refreshPlanStatus 的状态刷新读改写防飘。
     */
    @Version
    private Integer version = 0;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
