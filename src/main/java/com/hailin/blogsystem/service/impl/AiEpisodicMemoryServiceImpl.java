package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.ai.memory.AiEpisodicMemoryIndexService;
import com.hailin.blogsystem.ai.memory.AiEpisodicMemoryRetrieveService;
import com.hailin.blogsystem.entity.AiEpisodicMemories;
import com.hailin.blogsystem.entity.dto.EpisodicMemoryExtractResult;
import com.hailin.blogsystem.entity.dto.EpisodicMemoryRagContext;
import com.hailin.blogsystem.entity.vo.AiEpisodicMemoryVO;
import com.hailin.blogsystem.mapper.AiEpisodicMemoryMapper;
import com.hailin.blogsystem.service.AiEpisodicMemoryService;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;


@Service
@RequiredArgsConstructor
@Slf4j
public class AiEpisodicMemoryServiceImpl extends ServiceImpl<AiEpisodicMemoryMapper, AiEpisodicMemories>
implements AiEpisodicMemoryService {

    private static final BigDecimal MIN_CONFIDENCE = new BigDecimal("0.60"); //BigDecimal
    private static final int MIN_IMPORTANCE = 6;
    private static final int MAX_PROMPT_CONTENT_LENGTH = 200;
    private static final String FALLBACK_PROJECT_KEY = "global";

    private final BlogAiProperties blogAiProperties;
    private final AiEpisodicMemoryIndexService aiEpisodicMemoryIndexService;
    private final AiEpisodicMemoryRetrieveService aiEpisodicMemoryRetrieveService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void saveExtractedMemory(Long userId, Long sessionId,
                                    EpisodicMemoryExtractResult result){
        if (!isValidResult(userId, result)) {
            return;
        }

        String projectKey = resolveProjectKey();
        String content = result.getContent().trim();
        String contentHash = sha256(projectKey + "\n" + normalizeType(result.getMemoryType()) + "\n" + content);

        AiEpisodicMemories existing = lambdaQuery()
                .eq(AiEpisodicMemories::getUserId, userId)
                .eq(AiEpisodicMemories::getProjectKey, projectKey)
                .eq(AiEpisodicMemories::getContentHash, contentHash)
                .one();

        if (existing != null) {
            log.info("Episodic Memory 硬去重命中，userId={}, memoryId={}", userId, existing.getId());
            return;
        }

        List<EpisodicMemoryRagContext> similarMemories =
                aiEpisodicMemoryRetrieveService.retrieveForDedupe(userId, projectKey, content);
        if (!similarMemories.isEmpty()) {
            log.info("Episodic Memory 软去重命中，userId={}, count={}", userId, similarMemories.size());
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        AiEpisodicMemories memory = new AiEpisodicMemories();
        memory.setUserId(userId);
        memory.setSessionId(sessionId);
        memory.setProjectKey(projectKey);
        memory.setMemoryType(normalizeType(result.getMemoryType()));
        memory.setTitle(limitText(result.getTitle().trim(), 120));
        memory.setContent(content);
        memory.setImportance(normalizeImportance(result.getImportance()));
        memory.setConfidence(normalizeConfidence(result.getConfidence()));
        memory.setSourceMessageIds(toJson(result.getSourceMessageIds()));
        memory.setContentHash(contentHash);
        memory.setOccurredAt(result.getOccurredAt() == null ? now : result.getOccurredAt());
        memory.setRetrievalCount(0);
        memory.setCreatedAt(now);
        memory.setUpdatedAt(now);

        save(memory);
        aiEpisodicMemoryIndexService.indexMemory(memory);

        log.info("Episodic Memory 已保存，userId={}, sessionId={}, memoryId={}, type={}",
                userId, sessionId, memory.getId(), memory.getMemoryType());

    }


    @Override
    @Transactional
    public String buildEpisodicPrompt(Long userId,String question){
        String projectKey = resolveProjectKey();
        List<EpisodicMemoryRagContext> memories =
                aiEpisodicMemoryRetrieveService.retrieveForPrompt(userId, projectKey, question);

        if (memories.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 历史事件记忆\n");
        sb.append("以下是和当前问题相关的历史事件、决策、计划或里程碑。");
        sb.append("请只在相关时参考，用于解释过去为什么这样决定，不要机械复述。\n\n");

        for (EpisodicMemoryRagContext memory : memories) {
            sb.append("- [").append(memory.memoryType()).append("] ");
            if (memory.occurredAt() != null) {
                sb.append(memory.occurredAt().toLocalDate()).append("：");
            }
            sb.append(limitText(memory.content(), MAX_PROMPT_CONTENT_LENGTH)).append("\n");
        }

        return sb.toString().trim();
    }

    @Override
    public List<AiEpisodicMemoryVO> listCurrentUserMemories() {
        Long userId = requireLogin();
        String projectKey = resolveProjectKey();

        return lambdaQuery()
                .eq(AiEpisodicMemories::getUserId, userId)
                .eq(AiEpisodicMemories::getProjectKey, projectKey)
                .orderByDesc(AiEpisodicMemories::getOccurredAt)
                .orderByDesc(AiEpisodicMemories::getCreatedAt)
                .list()
                .stream()
                .map(AiEpisodicMemoryVO::from)
                .toList();
    }

    @Override
    @Transactional
    public void deleteMemory(Long id) {
        Long userId = requireLogin();
        String projectKey = resolveProjectKey();

        if (id == null) {
            throw new IllegalArgumentException("记忆ID不能为空");
        }

        AiEpisodicMemories memory = lambdaQuery()
                .eq(AiEpisodicMemories::getId, id)
                .eq(AiEpisodicMemories::getUserId, userId)
                .eq(AiEpisodicMemories::getProjectKey, projectKey)
                .one();

        if (memory == null) {
            throw new IllegalArgumentException("情景记忆不存在");
        }

        removeById(id);
        aiEpisodicMemoryIndexService.deleteMemoryIndex(id);
    }

    private boolean isValidResult(Long userId, EpisodicMemoryExtractResult result) {
        if (userId == null || result == null) {
            return false;
        }
        if (!Boolean.TRUE.equals(result.getShouldRemember())) {
            return false;
        }
        if (!isAllowedType(result.getMemoryType())) {
            return false;
        }
        if (result.getTitle() == null || result.getTitle().isBlank()) {
            return false;
        }
        if (result.getContent() == null || result.getContent().isBlank()) {
            return false;
        }
        return normalizeConfidence(result.getConfidence()).compareTo(MIN_CONFIDENCE) >= 0
                && normalizeImportance(result.getImportance()) >= MIN_IMPORTANCE;
    }

    private boolean isAllowedType(String memoryType) {
        if (memoryType == null) {
            return false;
        }
        String type = memoryType.trim().toUpperCase(Locale.ROOT);
        return "DECISION".equals(type)
                || "EVENT".equals(type)
                || "MILESTONE".equals(type)
                || "PLAN".equals(type);
    }

    private String normalizeType(String memoryType) {
        return memoryType.trim().toUpperCase(Locale.ROOT);
    }

    private BigDecimal normalizeConfidence(BigDecimal confidence) {
        if (confidence == null) {
            return new BigDecimal("0.80");
        }
        if (confidence.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (confidence.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        return confidence;
    }

    private Integer normalizeImportance(Integer importance) {
        if (importance == null) {
            return 6;
        }
        if (importance < 1) {
            return 1;
        }
        if (importance > 10) {
            return 10;
        }
        return importance;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("生成情景记忆哈希失败", e);
        }
    }

    private String limitText(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }

    private Long requireLogin() {
        Long userId = UserContext.get();
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }
        return userId;
    }

    private String resolveProjectKey() {
        if (blogAiProperties == null
                || blogAiProperties.getProjectKey() == null
                || blogAiProperties.getProjectKey().isBlank()) {
            return FALLBACK_PROJECT_KEY;
        }
        return blogAiProperties.getProjectKey().trim();
    }

}
