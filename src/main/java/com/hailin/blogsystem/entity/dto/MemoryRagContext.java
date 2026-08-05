package com.hailin.blogsystem.entity.dto;

import java.math.BigDecimal;
//把 ES/VectorStore 召回出来的 memory 文档转成统一对象，后面拼 prompt 时不用直接操作 Document
public record MemoryRagContext(
        Long memoryId,
        Long userId,
        String memoryType,
        String memoryKey,
        String content,
        BigDecimal confidence,
        Integer importance
) {
}
