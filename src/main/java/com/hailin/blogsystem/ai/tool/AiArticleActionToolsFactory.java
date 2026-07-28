package com.hailin.blogsystem.ai.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiArticleActionToolsFactory {

    private final AiToolActionRegistry registry;

    public AiArticleActionTools create(String requestId){
        return new AiArticleActionTools(requestId,registry);
    }
}
