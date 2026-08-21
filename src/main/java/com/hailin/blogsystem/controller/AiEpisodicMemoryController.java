package com.hailin.blogsystem.controller;

import com.hailin.blogsystem.entity.vo.AiEpisodicMemoryVO;
import com.hailin.blogsystem.service.AiEpisodicMemoryService;
import com.hailin.blogsystem.utils.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ai/episodic-memories")
@RequiredArgsConstructor
public class AiEpisodicMemoryController {

    private final AiEpisodicMemoryService aiEpisodicMemoryService;

    @GetMapping
    public Result<List<AiEpisodicMemoryVO>> listMemories() {
        return Result.success(aiEpisodicMemoryService.listCurrentUserMemories());
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteMemory(@PathVariable Long id) {
        aiEpisodicMemoryService.deleteMemory(id);
        return Result.success();
    }
}
