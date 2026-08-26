package com.hailin.blogsystem.service;

import com.hailin.blogsystem.entity.vo.AiChatEventVO;
import reactor.core.publisher.Flux;

public interface AiWorkflowStreamService {
    Flux<AiChatEventVO> approve(Long id);

    Flux<AiChatEventVO> reject(Long id,String feedback);

    Flux<AiChatEventVO> retry(Long id);

    Flux<AiChatEventVO> approve(Long id, String idempotencyKey);

    Flux<AiChatEventVO> reject(
            Long id,
            String feedback,
            String idempotencyKey
    );

    Flux<AiChatEventVO> retry(Long id, String idempotencyKey);
}
