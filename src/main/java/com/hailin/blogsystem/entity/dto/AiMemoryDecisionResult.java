package com.hailin.blogsystem.entity.dto;

import lombok.Data;

@Data
public class AiMemoryDecisionResult {

    private String action;
    private Long targetMemoryId;
    private String mergedContent;
    private String reason;
}
