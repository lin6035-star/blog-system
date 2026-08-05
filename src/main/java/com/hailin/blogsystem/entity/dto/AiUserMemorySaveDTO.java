package com.hailin.blogsystem.entity.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class AiUserMemorySaveDTO {

    private String memoryType;
    private String memoryKey;
    private String content;
    private String source;
    private BigDecimal confidence;
    private Integer importance;
}
