package com.hailin.blogsystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hailin.blogsystem.entity.AiEpisodicMemories;
import com.hailin.blogsystem.entity.dto.EpisodicMemoryExtractResult;
import com.hailin.blogsystem.entity.vo.AiEpisodicMemoryVO;

import java.util.List;

public interface AiEpisodicMemoryService extends IService<AiEpisodicMemories> {

    void saveExtractedMemory(Long userId, Long sessionId, EpisodicMemoryExtractResult result);

    String buildEpisodicPrompt(Long userId, String question);

    List<AiEpisodicMemoryVO> listCurrentUserMemories();

    void deleteMemory(Long id);
}
