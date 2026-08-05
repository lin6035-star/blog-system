package com.hailin.blogsystem.ai.memory;

import com.hailin.blogsystem.entity.dto.AiMemoryDecisionResult;
import com.hailin.blogsystem.entity.dto.MemoryRagContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AiMemoryDecisionSanitizer {

    private static final String ACTION_CREATE = "CREATE";
    private static final String ACTION_UPDATE = "UPDATE";
    private static final String ACTION_MERGE = "MERGE";
    private static final String ACTION_IGNORE = "IGNORE";

    public AiMemoryDecisionResult sanitize(
            AiMemoryDecisionResult decision,
            List<MemoryRagContext> candidateMemories
    ) {
        AiMemoryDecisionResult sanitized = new AiMemoryDecisionResult();
        if (decision == null) {
            sanitized.setAction(ACTION_CREATE);
            sanitized.setReason("LLM 未返回有效决策，默认作为新记忆候选。");
            return sanitized;
        }

        String action = normalizeAction(decision.getAction());
        Long targetMemoryId = decision.getTargetMemoryId();

        if (requiresTarget(action) && !containsMemoryId(candidateMemories, targetMemoryId)) {
            sanitized.setAction(ACTION_CREATE);
            sanitized.setReason("LLM 返回的目标记忆不在召回候选中，降级为 CREATE。");
            return sanitized;
        }

        if (ACTION_MERGE.equals(action) && isBlank(decision.getMergedContent())) {
            sanitized.setAction(ACTION_UPDATE);
            sanitized.setTargetMemoryId(targetMemoryId);
            sanitized.setReason(firstNonBlank(decision.getReason(), "MERGE 缺少合并内容，降级为 UPDATE。"));
            return sanitized;
        }

        sanitized.setAction(action);
        sanitized.setTargetMemoryId(requiresTarget(action) ? targetMemoryId : null);
        sanitized.setMergedContent(trimToNull(decision.getMergedContent()));
        sanitized.setReason(trimToNull(decision.getReason()));
        return sanitized;
    }

    private String normalizeAction(String action) {
        if (action == null) {
            return ACTION_CREATE;
        }

        String normalized = action.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case ACTION_UPDATE, ACTION_MERGE, ACTION_IGNORE -> normalized;
            default -> ACTION_CREATE;
        };
    }

    private boolean requiresTarget(String action) {
        return ACTION_UPDATE.equals(action) || ACTION_MERGE.equals(action);
    }

    private boolean containsMemoryId(List<MemoryRagContext> candidateMemories, Long targetMemoryId) {
        if (targetMemoryId == null || candidateMemories == null || candidateMemories.isEmpty()) {
            return false;
        }

        Set<Long> memoryIds = candidateMemories.stream()
                .map(MemoryRagContext::memoryId)
                .collect(Collectors.toSet());
        return memoryIds.contains(targetMemoryId);
    }

    private String firstNonBlank(String first, String fallback) {
        String normalized = trimToNull(first);
        return normalized == null ? fallback : normalized;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String trimToNull(String value) {
        if (isBlank(value)) {
            return null;
        }
        return value.trim();
    }
}
