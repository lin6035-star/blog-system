package com.hailin.blogsystem.entity.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record EpisodicMemoryRagContext(
        Long memoryId,
        Long userId,
        String projectKey,
        String memoryType,
        String title,
        String content,
        BigDecimal confidence,
        Integer importance,
        LocalDateTime occurredAt
) {
}