package com.hailin.blogsystem.service;

public interface AiMemoryCandidateExtractorService {

    //聊天结束后分析这一轮对话，必要时写入 ai_user_memory_candidates
    void extractAfterChat(Long userId,Long sessionId,Long messageId, String userMessage, String assistantReply);
}
