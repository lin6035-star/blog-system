package com.hailin.blogsystem.service;

import com.hailin.blogsystem.entity.AiPrompt;
import reactor.core.publisher.Flux;

public interface AiModelService {
    String chat(AiPrompt prompt,String requestId);

    Flux<String> streamChat(AiPrompt prompt,String requestId);
}