package com.hailin.blogsystem.service;

import com.hailin.blogsystem.entity.AiMessages;

import java.util.List;

public interface AiConversationMemoryService {
    //查询某个会话最近n条消息，按时间升序
    List<AiMessages> getRecentMessages(Long sessionId,int maxMessages);
}
