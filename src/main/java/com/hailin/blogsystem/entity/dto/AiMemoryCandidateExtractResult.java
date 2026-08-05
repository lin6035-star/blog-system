package com.hailin.blogsystem.entity.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data  //新建LLM记忆输出DTO
//这个DTO对应模型输出的一条候选记忆
public class AiMemoryCandidateExtractResult {

    private String memoryType;
    private String memoryKey;
    private String content;
    private String candidateAction;
    private String reason;
    private BigDecimal confidence;
    private Integer importance;
}
