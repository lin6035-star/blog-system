package com.hailin.blogsystem.service;

import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.entity.AiPrompt;
import reactor.core.publisher.Flux;

public interface AiModelService {
    // 【已废弃】非流式接口，前端已全面切到流式，暂时注释，后续删除
    // String chat(AiPrompt prompt,String requestId);

    //usageAccumulator 由调用方创建，流结束后读取本次调用的 token 用量（含工具调用多轮累计）
    Flux<String> streamChat(AiPrompt prompt,String requestId,TokenUsageAccumulator usageAccumulator);
}