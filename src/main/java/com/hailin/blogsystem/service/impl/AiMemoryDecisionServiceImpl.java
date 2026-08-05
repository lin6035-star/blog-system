package com.hailin.blogsystem.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.memory.AiMemoryDecisionSanitizer;
import com.hailin.blogsystem.entity.dto.AiMemoryCandidateExtractResult;
import com.hailin.blogsystem.entity.dto.AiMemoryDecisionResult;
import com.hailin.blogsystem.entity.dto.MemoryRagContext;
import com.hailin.blogsystem.service.AiMemoryDecisionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiMemoryDecisionServiceImpl implements AiMemoryDecisionService {

    private static final int MAX_CANDIDATE_COUNT = 5;

    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;
    private final AiMemoryDecisionSanitizer aiMemoryDecisionSanitizer;

    @Override
    public AiMemoryDecisionResult decide(
            AiMemoryCandidateExtractResult newMemory,
            List<MemoryRagContext> candidateMemories
    ) {
        List<MemoryRagContext> safeCandidates = safeCandidates(candidateMemories);
        if (safeCandidates.isEmpty()) {
            return createDecision("没有召回到相似旧记忆，作为新候选记忆。");
        }

        try {
            String response = chatClientBuilder.build()
                    .prompt()
                    .system("""
                            你是一个长期记忆更新决策器。
                            你的任务不是提取新记忆，而是判断“新 Memory”和“已召回的旧 Memory”之间应该如何处理。

                            动作只能是：
                            - CREATE：新 Memory 是独立的新长期信息。
                            - UPDATE：新 Memory 替换某条旧 Memory，例如状态推进、偏好发生变化。
                            - MERGE：新 Memory 补充某条旧 Memory，应该合并成更完整的一条。
                            - IGNORE：新 Memory 已被旧 Memory 覆盖，或不值得进入长期记忆。

                            判断原则：
                            - Embedding 召回只代表可能相关，不代表可以直接覆盖。
                            - 不要因为语义相似就 UPDATE；要判断业务含义是不是同一条长期记忆。
                            - PROJECT_STATE 中“完成 A”和“完成 B”通常是 MERGE，不是互相覆盖。
                            - 偏好发生反向变化时通常是 UPDATE。
                            - 如果 UPDATE 或 MERGE，targetMemoryId 必须来自候选旧 Memory。
                            - 只能返回 JSON 对象，不要返回 Markdown，不要解释。

                            JSON 格式：
                            {
                              "action": "MERGE",
                              "targetMemoryId": 18,
                              "mergedContent": "已完成 RAG、Memory，当前准备开发 Workflow。",
                              "reason": "属于同一个项目推进阶段，新信息是在补充旧状态。"
                            }
                            """)
                    .user("""
                            新 Memory：
                            memoryType=%s
                            memoryKey=%s
                            content=%s

                            候选旧 Memory：
                            %s
                            """.formatted(
                            nullToEmpty(newMemory.getMemoryType()),
                            nullToEmpty(newMemory.getMemoryKey()),
                            nullToEmpty(newMemory.getContent()),
                            formatCandidateMemories(safeCandidates)
                    ))
                    .call()
                    .content();

            AiMemoryDecisionResult decision = parseDecision(response);
            return aiMemoryDecisionSanitizer.sanitize(decision, safeCandidates);
        } catch (Exception e) {
            log.warn("Memory 更新决策失败，默认作为 CREATE，memoryType={}, memoryKey={}",
                    newMemory.getMemoryType(), newMemory.getMemoryKey(), e);
            return createDecision("Memory 更新决策失败，默认作为新候选记忆。");
        }
    }

    private List<MemoryRagContext> safeCandidates(List<MemoryRagContext> candidateMemories) {
        if (candidateMemories == null || candidateMemories.isEmpty()) {
            return List.of();
        }
        return candidateMemories.stream()
                .filter(memory -> memory.memoryId() != null)
                .limit(MAX_CANDIDATE_COUNT)
                .toList();
    }

    private String formatCandidateMemories(List<MemoryRagContext> candidateMemories) {
        StringBuilder sb = new StringBuilder();
        for (MemoryRagContext memory : candidateMemories) {
            sb.append("- id=").append(memory.memoryId()).append("\n")
                    .append("  memoryType=").append(memory.memoryType()).append("\n")
                    .append("  memoryKey=").append(memory.memoryKey()).append("\n")
                    .append("  content=").append(memory.content()).append("\n");
        }
        return sb.toString();
    }

    private AiMemoryDecisionResult parseDecision(String response) throws Exception {
        if (response == null || response.isBlank()) {
            return null;
        }

        String json = extractJsonObject(response);
        if (json == null) {
            return null;
        }

        return objectMapper.readValue(json, AiMemoryDecisionResult.class);
    }

    private String extractJsonObject(String response) {
        int start = response.indexOf("{");
        int end = response.lastIndexOf("}");
        if (start < 0 || end < start) {
            return null;
        }
        return response.substring(start, end + 1);
    }

    private AiMemoryDecisionResult createDecision(String reason) {
        AiMemoryDecisionResult decision = new AiMemoryDecisionResult();
        decision.setAction("CREATE");
        decision.setReason(reason);
        return decision;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
