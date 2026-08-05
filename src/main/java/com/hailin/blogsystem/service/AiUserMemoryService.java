package com.hailin.blogsystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hailin.blogsystem.entity.AiUserMemories;
import com.hailin.blogsystem.entity.dto.AiUserMemorySaveDTO;
import com.hailin.blogsystem.entity.vo.AiUserMemoryVO;

import java.util.List;

public interface AiUserMemoryService extends IService<AiUserMemories> {

    List<AiUserMemoryVO> listCurrentUserMemories();

    List<AiUserMemories> listPromptMemories(Long userId);

    void saveOrUpdateMemory(AiUserMemorySaveDTO dto);

    void disableMemory(Long id);

    String buildMemoryPrompt(Long userId);

    String buildMemoryPrompt(Long userId, String question);

    void updateMemoryById(Long id,AiUserMemorySaveDTO dto);
}
