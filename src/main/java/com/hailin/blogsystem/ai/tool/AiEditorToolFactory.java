package com.hailin.blogsystem.ai.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiEditorToolFactory {

    private final AiToolActionRegistry registry;

    public AiEditorTools create(String requestId){
        return new AiEditorTools(requestId,registry);
    }
}
