package com.hailin.blogsystem.service;

public interface AiEpisodicMemoryExtractorService {

    void extractAfterChat(
            Long userId,
            Long sessionId,
            Long userMessageId,
            Long assistantMessageId,
            String userMessage,
            String assistantReply
    );
}
