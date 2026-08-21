package com.hailin.blogsystem.entity.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class EpisodicMemoryExtractResult {

    private Boolean shouldRemember;
    private String memoryType;
    private String title;
    private String content;
    private Integer importance;
    private BigDecimal confidence;
    private List<Long> sourceMessageIds;
    private LocalDateTime occurredAt;
}