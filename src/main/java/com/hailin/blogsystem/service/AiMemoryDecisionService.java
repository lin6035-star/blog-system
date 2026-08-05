package com.hailin.blogsystem.service;

import com.hailin.blogsystem.entity.dto.AiMemoryCandidateExtractResult;
import com.hailin.blogsystem.entity.dto.AiMemoryDecisionResult;
import com.hailin.blogsystem.entity.dto.MemoryRagContext;

import java.util.List;

public interface AiMemoryDecisionService {

    AiMemoryDecisionResult decide(
            AiMemoryCandidateExtractResult newMemory,
            List<MemoryRagContext> candidateMemories
    );
}
