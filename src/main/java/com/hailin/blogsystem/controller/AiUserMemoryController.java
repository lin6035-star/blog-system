package com.hailin.blogsystem.controller;

import com.hailin.blogsystem.entity.dto.AiUserMemorySaveDTO;
import com.hailin.blogsystem.entity.vo.AiUserMemoryVO;
import com.hailin.blogsystem.service.AiUserMemoryService;
import com.hailin.blogsystem.utils.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ai/memories")
@RequiredArgsConstructor
public class AiUserMemoryController {

    private final AiUserMemoryService aiUserMemoryService;

    // ================================================================
    // 正式记忆
    // ================================================================

    @GetMapping
    public Result<List<AiUserMemoryVO>> listMemories() {
        return Result.success(aiUserMemoryService.listCurrentUserMemories());
    }

    @PostMapping
    public Result<Void> saveOrUpdateMemory(@RequestBody AiUserMemorySaveDTO dto) {
        aiUserMemoryService.saveOrUpdateMemory(dto);
        return Result.success();
    }

    @PutMapping("/{id}")
    public Result<Void> updateMemory(@PathVariable Long id, @RequestBody AiUserMemorySaveDTO dto) {
        aiUserMemoryService.updateMemoryById(id, dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> disableMemory(@PathVariable Long id) {
        aiUserMemoryService.disableMemory(id);
        return Result.success();
    }
}
