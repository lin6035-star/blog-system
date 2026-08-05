package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hailin.blogsystem.ai.memory.AiMemoryIndexService;
import com.hailin.blogsystem.ai.memory.AiMemoryRetrieveService;
import com.hailin.blogsystem.entity.AiUserMemories;
import com.hailin.blogsystem.entity.dto.AiUserMemorySaveDTO;
import com.hailin.blogsystem.entity.dto.MemoryRagContext;
import com.hailin.blogsystem.entity.vo.AiUserMemoryVO;
import com.hailin.blogsystem.mapper.AiUserMemoryMapper;
import com.hailin.blogsystem.service.AiUserMemoryService;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AiUserMemoryServiceImpl extends ServiceImpl<AiUserMemoryMapper, AiUserMemories>
        implements AiUserMemoryService{

    private static final int ENABLED = 1;
    private static final int DISABLED = 0;
    private static final String DEFAULT_SOURCE = "USER_EXPLICIT";
    private static final int MAX_PROMPT_MEMORIES = 25;
    private static final BigDecimal MIN_PROMPT_CONFIDENCE = new BigDecimal("0.70");
    private static final int MAX_ALWAYS_PROFILE_MEMORIES = 2;
    private static final int MAX_ALWAYS_PREFERENCE_MEMORIES = 3;
    private static final int MAX_RETRIEVED_MEMORIES = 5;

    private final AiMemoryIndexService aiMemoryIndexService;
    private final AiMemoryRetrieveService aiMemoryRetrieveService;

    @Override
    public List<AiUserMemoryVO> listCurrentUserMemories() {
       Long userId =  requireLogin();

        return lambdaQuery()
                .eq(AiUserMemories::getUserId, userId)
                .eq(AiUserMemories::getEnabled, ENABLED)
                .orderByDesc(AiUserMemories::getImportance)
                .orderByDesc(AiUserMemories::getUpdatedAt)
                .list()
                .stream()
                .map(AiUserMemoryVO::from)
                .toList();
    }

    @Override
    public List<AiUserMemories> listPromptMemories(Long userId) {
        if (userId == null) {
            return List.of();
        }

        return lambdaQuery()
                .eq(AiUserMemories::getUserId,userId)
                .eq(AiUserMemories::getEnabled,ENABLED)
                .ge(AiUserMemories::getConfidence, MIN_PROMPT_CONFIDENCE)
                .orderByDesc(AiUserMemories::getImportance)
                .orderByDesc(AiUserMemories::getUpdatedAt)
                .last("LIMIT " + MAX_PROMPT_MEMORIES)
                .list();
    }

    @Override
    @Transactional
    public void saveOrUpdateMemory(AiUserMemorySaveDTO dto) {

        Long userId = requireLogin();

        if (dto == null) {
            throw new IllegalArgumentException("请求参数不能为空");
        }

        String memoryType = normalizeRequired(dto.getMemoryType(), "记忆类型不能为空").toUpperCase(Locale.ROOT);
        String memoryKey = normalizeRequired(dto.getMemoryKey(), "记忆键不能为空");
        String content = normalizeRequired(dto.getContent(), "记忆内容不能为空");

        LocalDateTime now = LocalDateTime.now();

        AiUserMemories existing = lambdaQuery()
                .eq(AiUserMemories::getUserId, userId)
                .eq(AiUserMemories::getMemoryType, memoryType)
                .eq(AiUserMemories::getMemoryKey, memoryKey)
                .one();

        if (existing == null) {
            AiUserMemories memory = new AiUserMemories();
            memory.setUserId(userId);
            memory.setMemoryType(memoryType);
            memory.setMemoryKey(memoryKey);
            memory.setContent(content);
            memory.setSource(normalizeSource(dto.getSource()));
            memory.setConfidence(normalizeConfidence(dto.getConfidence()));
            memory.setImportance(normalizeImportance(dto.getImportance()));
            memory.setEnabled(ENABLED);
            memory.setCreatedAt(now);
            memory.setUpdatedAt(now);

            save(memory);
            aiMemoryIndexService.indexMemory(memory);

            return;
        }

        existing.setContent(content);
        existing.setSource(normalizeSource(dto.getSource()));
        existing.setConfidence(normalizeConfidence(dto.getConfidence()));
        existing.setImportance(normalizeImportance(dto.getImportance()));
        existing.setEnabled(ENABLED);
        existing.setUpdatedAt(now);

        updateById(existing);
        aiMemoryIndexService.indexMemory(existing);
    }

    @Override
    @Transactional
    //候选说“我要更新某条正式记忆”时，不再模糊地靠 memoryType + memoryKey 查，
    // 而是按 target_memory_id 精准更新，并且校验这条记忆属于当前用户
    public void updateMemoryById(Long id, AiUserMemorySaveDTO dto) {
        Long userId = requireLogin();

        if (id == null) {
            throw new IllegalArgumentException("目标记忆ID不能为空");
        }
        if (dto == null) {
            throw new IllegalArgumentException("请求参数不能为空");
        }

        AiUserMemories memory = lambdaQuery()
                .eq(AiUserMemories::getId, id)
                .eq(AiUserMemories::getUserId, userId)
                .eq(AiUserMemories::getEnabled, ENABLED)
                .one();

        if (memory == null) {
            throw new IllegalArgumentException("目标记忆不存在");
        }

        memory.setMemoryType(normalizeRequired(dto.getMemoryType(), "记忆类型不能为空").toUpperCase(Locale.ROOT));
        memory.setMemoryKey(normalizeRequired(dto.getMemoryKey(), "记忆键不能为空"));
        memory.setContent(normalizeRequired(dto.getContent(), "记忆内容不能为空"));
        memory.setSource(normalizeSource(dto.getSource()));
        memory.setConfidence(normalizeConfidence(dto.getConfidence()));
        memory.setImportance(normalizeImportance(dto.getImportance()));
        memory.setUpdatedAt(LocalDateTime.now());

        updateById(memory);
        aiMemoryIndexService.indexMemory(memory);
    }

    @Override
    @Transactional
    public void disableMemory(Long id) {
        Long userId = requireLogin();

        boolean updated = lambdaUpdate()
                .eq(AiUserMemories::getId, id)
                .eq(AiUserMemories::getUserId, userId)
                .set(AiUserMemories::getEnabled, DISABLED)
                .set(AiUserMemories::getUpdatedAt, LocalDateTime.now())
                .update();

        if (!updated) {
            throw new IllegalArgumentException("记忆不存在");
        }

        aiMemoryIndexService.deleteMemoryIndex(id);
    }

    @Override
    public String buildMemoryPrompt(Long userId) {
        return buildMemoryPrompt(userId, null);
    }

    //常驻记忆保证 AI 一直知道“你是谁、喜欢怎样回答”；
    // 向量召回负责找和当前问题语义相关的记忆，比如生活爱好、项目状态、阶段目标。
    @Override
    public String buildMemoryPrompt(Long userId, String question) {
        List<AiUserMemories> alwaysMemories = listAlwaysPromptMemories(userId);
        List<MemoryRagContext> retrievedMemories = aiMemoryRetrieveService.retrieve(userId, question);

        List<AiUserMemories> memories = mergePromptMemories(alwaysMemories, retrievedMemories);

        if (memories.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 用户长期记忆\n");
        sb.append("以下是用户长期记忆，包括少量常驻记忆和与当前问题语义相关的记忆。");
        sb.append("请用它们理解用户背景、偏好和项目状态，但不要机械复述；");
        sb.append("只有在和当前问题相关时才自然参考。\n\n");

        appendMemoryGroup(sb, memories, "PROFILE");
        appendMemoryGroup(sb, memories, "PREFERENCE");
        appendMemoryGroup(sb, memories, "PROJECT_STATE");

        return sb.toString().trim();
    }
    private List<AiUserMemories> listAlwaysPromptMemories(Long userId) {
        if (userId == null) {
            return List.of();
        }

        List<AiUserMemories> profiles = listAlwaysMemoriesByType(userId, "PROFILE", 2);
        List<AiUserMemories> preferences = listAlwaysMemoriesByType(userId, "PREFERENCE", 3);

        return java.util.stream.Stream.concat(profiles.stream(), preferences.stream())
                .toList();
    }
    private List<AiUserMemories> listAlwaysMemoriesByType(Long userId, String memoryType, int limit) {
        return lambdaQuery()
                .eq(AiUserMemories::getUserId, userId)
                .eq(AiUserMemories::getEnabled, ENABLED)
                .eq(AiUserMemories::getMemoryType, memoryType)
                .ge(AiUserMemories::getConfidence, MIN_PROMPT_CONFIDENCE)
                .orderByDesc(AiUserMemories::getImportance)
                .orderByDesc(AiUserMemories::getUpdatedAt)
                .last("LIMIT " + limit)
                .list();
    }
    private List<AiUserMemories> mergePromptMemories(
            List<AiUserMemories> alwaysMemories,
            List<MemoryRagContext> retrievedMemories
    ) {
        Map<Long, AiUserMemories> merged = new LinkedHashMap<>();

        for (AiUserMemories memory : alwaysMemories) {
            if (memory.getId() != null) {
                merged.put(memory.getId(), memory);
            }
        }

        for (MemoryRagContext context : retrievedMemories) {
            if (context.memoryId() == null || merged.containsKey(context.memoryId())) {
                continue;
            }

            AiUserMemories memory = new AiUserMemories();
            memory.setId(context.memoryId());
            memory.setUserId(context.userId());
            memory.setMemoryType(context.memoryType());
            memory.setMemoryKey(context.memoryKey());
            memory.setContent(context.content());
            memory.setConfidence(context.confidence());
            memory.setImportance(context.importance());
            memory.setEnabled(ENABLED);
            merged.put(memory.getId(), memory);
        }

        return merged.values().stream().toList();
    }

    private void appendMemoryGroup(StringBuilder sb, List<AiUserMemories> memories, String memoryType) {
        List<AiUserMemories> group = memories.stream()
                .filter(memory -> memoryType.equals(memory.getMemoryType()))
                .toList();

        if (group.isEmpty()) {
            return;
        }

        sb.append("### ").append(memoryType).append("\n");
        for (AiUserMemories memory : group) {
            sb.append("- ").append(memory.getContent()).append("\n");
        }
        sb.append("\n");
    }

    private Long requireLogin() {
        Long userId = UserContext.get();
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }
        return userId;
    }

    private String normalizeRequired(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String normalizeSource(String source) {
        if (source == null || source.trim().isEmpty()) {
            return DEFAULT_SOURCE;
        }
        return source.trim().toUpperCase(Locale.ROOT);
    }

    private BigDecimal normalizeConfidence(BigDecimal confidence) {
        if (confidence == null) {
            return BigDecimal.ONE;
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
            return 5;
        }
        if (importance < 1) {
            return 1;
        }
        if (importance > 10) {
            return 10;
        }
        return importance;
    }
}
