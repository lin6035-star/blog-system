package com.hailin.blogsystem.ai.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiNavigationToolsFactory {
    private final AiToolActionRegistry registry;

    public AiNavigationTools create(String requestId) {
        return new AiNavigationTools(requestId, registry);
    }
}
