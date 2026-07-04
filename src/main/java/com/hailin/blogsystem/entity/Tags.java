package com.hailin.blogsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tags")
public class Tags {

    @TableId( type = IdType.AUTO)
    private Long id;
    private String name;  //标签名称
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
