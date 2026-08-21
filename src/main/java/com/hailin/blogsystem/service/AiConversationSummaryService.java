package com.hailin.blogsystem.service;

import com.hailin.blogsystem.entity.vo.AiConversationSummaryStatusVO;

public interface AiConversationSummaryService {

    void compressAfterChat(Long userId, Long sessionId);

    String buildSummaryPrompt(Long userId, Long sessionId);

    /**
     * 查询当前登录用户某个会话的压缩状态（压缩中标记 + 最近压缩时间 + 已压缩消息数）。
     */
    AiConversationSummaryStatusVO getSummaryStatus(Long sessionId);

    /**
     * 删除会话时同步删除其压缩摘要。
     */
    void deleteBySession(Long userId, Long sessionId);
}