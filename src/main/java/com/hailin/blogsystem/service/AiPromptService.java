package com.hailin.blogsystem.service;

import com.hailin.blogsystem.entity.AiPrompt;
import com.hailin.blogsystem.entity.dto.PageContextDTO;

public interface AiPromptService {
    /** 根据会话 ID 和页面上下文，拼出完整 prompt（含历史记忆） */
    AiPrompt buildPrompt(String userMessage, PageContextDTO pageContext, Long sessionId);
}
