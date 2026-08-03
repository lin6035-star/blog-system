package com.hailin.blogsystem.service;

import com.hailin.blogsystem.entity.AiPrompt;
import reactor.core.publisher.Flux;

public interface AiModelService {
    // 【已废弃】非流式接口，前端已全面切到流式，暂时注释，后续删除
    // String chat(AiPrompt prompt,String requestId);

    Flux<String> streamChat(AiPrompt prompt,String requestId);
}