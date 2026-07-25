package com.hailin.blogsystem.service;

import reactor.core.publisher.Flux;

public interface AiModelService {
    String chat(String message, String pageContextJson);

    Flux<String> streamChat(String message, String pageContextJson);
}