package com.hailin.blogsystem.entity.vo;

import java.time.LocalDateTime;

/**
 * 会话压缩状态，供前端显示"正在自动压缩上下文 / 已自动压缩上下文"。
 */
public record AiConversationSummaryStatusVO(
        boolean compressing,
        LocalDateTime lastCompressedAt,
        int coveredMessageCount
) {
}
