package com.hailin.blogsystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hailin.blogsystem.entity.AiMessages;
import com.hailin.blogsystem.entity.dto.AiChatDTO;
import com.hailin.blogsystem.entity.vo.AiChatEventVO;
import com.hailin.blogsystem.entity.vo.AiChatVO;
import com.hailin.blogsystem.entity.vo.AiMessageVO;
import reactor.core.publisher.Flux;

import java.util.List;

public interface AiMessageService extends IService<AiMessages> {
    List<AiMessageVO> getMessages(String id);

    /*AiChatVO chat(AiChatDTO aiChatDTO);*/

    Flux<AiChatEventVO> streamChat(AiChatDTO aiChatDTO);

    void deleteMessage(Long sessionId, Long messageId);
}
