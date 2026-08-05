package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.memory.AiMemoryRetrieveService;
import com.hailin.blogsystem.entity.AiUserMemoryCandidate;
import com.hailin.blogsystem.entity.dto.AiMemoryCandidateExtractResult;
import com.hailin.blogsystem.entity.dto.AiMemoryDecisionResult;
import com.hailin.blogsystem.entity.dto.MemoryRagContext;
import com.hailin.blogsystem.mapper.AiUserMemoryCandidateMapper;
import com.hailin.blogsystem.service.AiMemoryCandidateExtractorService;
import com.hailin.blogsystem.service.AiMemoryDecisionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 候选记忆提取器。
 * 职责：聊天结束后分析本轮对话，规则预筛命中后写入 ai_user_memory_candidates。
 * 现阶段用规则候选验证链路，后续接入 LLM JSON 提取。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiMemoryCandidateExtractorServiceImpl implements AiMemoryCandidateExtractorService {

    private final AiUserMemoryCandidateMapper aiUserMemoryCandidateMapper;
    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;
    private final AiMemoryRetrieveService aiMemoryRetrieveService;
    private final AiMemoryDecisionService aiMemoryDecisionService;

    private static final String STATUS_PENDING = "PENDING";
    private static final String ACTION_CREATE = "CREATE";
    private static final String SOURCE_AI_EXTRACTED = "AI_EXTRACTED";
    private static final String ACTION_UPDATE = "UPDATE";
    private static final String ACTION_MERGE = "MERGE";
    private static final String ACTION_IGNORE = "IGNORE";

    @Override
    @Async("memoryCandidateTaskExecutor")
    public void extractAfterChat(Long userId, Long sessionId, Long messageId,
                                  String userMessage, String assistantReply) {
        if (userId == null) {
            return;
        }
        if (!shouldExtract(userMessage)) {
            return;
        }

        try{
            List<AiMemoryCandidateExtractResult> results = extractCandidatesByLlm(userMessage,assistantReply);
            if(results == null || results.isEmpty()){
                log.info("候选记忆提取结果为空，userId={}, sessionId={}", userId, sessionId);
                return;
            }

            for(AiMemoryCandidateExtractResult result : results){
                saveExtractedCandidate(userId,sessionId,messageId,result);
            }

            log.info("候选记忆提取完成，userId={}, sessionId={}, count={}", userId, sessionId, results.size());
        } catch (Exception e) {
            log.warn("候选记忆提取失败，userId={}, sessionId={}", userId, sessionId, e);
        }


    }

    // ================================================================
    // 规则预筛：不是每轮对话都调用 LLM
    // ================================================================

    private boolean shouldExtract(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }

        String text = userMessage.replaceAll("\\s+", "");

        return containsAny(text,
                "记住",
                "以后",
                "我喜欢",
                "我更喜欢",
                "我的目标",
                "我现在在做",
                "这个项目",
                "我们决定",
                "先不做",
                "后面再做"
        );
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private List<AiMemoryCandidateExtractResult> extractCandidatesByLlm(String userMessage, String assistantReply){
        String response = chatClientBuilder.build()
                .prompt()
                .system(
                        """
                    你是一个用户长期记忆提取器。
                    你的任务是从一轮用户与 AI 的对话中，判断是否存在值得长期保存的用户记忆。

                    只允许提取三类记忆：
                    1. PROFILE：用户画像，例如学习阶段、技术方向、长期目标。
                    2. PREFERENCE：用户偏好，例如回答风格、学习方式、协作方式、技术解释深度、界面审美。
                    3. PROJECT_STATE：项目状态，例如当前正在做什么、已完成什么、决定暂缓什么。

                    严格规则：
                    - 只提取用户明确表达的信息。
                    - 不要根据 AI 回复猜测用户事实。
                    - 不提取临时闲聊、一次性报错、普通技术问答。
                    - 如果没有值得保存的记忆，返回空数组 []。
                    - 只能返回 JSON 数组，不要返回 Markdown，不要解释。
                    - memoryType 只能是 PROFILE、PREFERENCE、PROJECT_STATE。
                    - memoryKey 不要自由编写，优先从稳定记忆维度中选择。
                    - PROFILE 可选 memoryKey：technical_direction、learning_stage、career_goal、general_profile。
                    - PREFERENCE 可选 memoryKey：learning_style、answer_style、collaboration_style、technical_depth、ui_style、general_preference。
                    - PROJECT_STATE 可选 memoryKey：current_focus、completed_work、deferred_work、project_decision、general_project_state。
                    - candidateAction 只表示初步建议，可以是 CREATE、UPDATE、MERGE、IGNORE，最终以后端决策为准。
                    - confidence 范围 0 到 1。
                    - importance 范围 1 到 10。
                    
                    JSON 格式：
                                        [
                                          {
                                            "memoryType": "PREFERENCE",
                                            "memoryKey": "learning_style",
                                            "content": "用户更喜欢先理解项目中的真实业务含义，再自己动手写代码。",
                                            "candidateAction": "UPDATE",
                                            "reason": "用户明确表达了自己的学习方式偏好。",
                                            "confidence": 0.95,
                                            "importance": 8
                                          }
                                        ]
                                        """
                )
                .user(
                        """
                    用户消息：
                    %s

                    AI 回复：
                    %s
                    """.formatted(limitText(userMessage, 1000), limitText(assistantReply, 1500))
                )
                .call()
                .content();

        return parseExtractResul(response);
    }

    private List<AiMemoryCandidateExtractResult> parseExtractResul(String response){
        if (response == null || response.isBlank()) {
            return List.of();
        }

        String json = extractJsonArray(response);
        if (json == null || json.isBlank()) {
            return List.of();
        }

        try{
            return objectMapper.readValue(json, new TypeReference<List<AiMemoryCandidateExtractResult>>() {});
        }catch(Exception e){
            log.warn("候选记忆 JSON 解析失败，response={}", response, e);
            return List.of();
        }
    }
    private String extractJsonArray(String response){
        int start = response.indexOf("[");
        int end = response.indexOf("]");

        if (start < 0 || end < start) {
            return null;
        }

        return response.substring(start, end + 1);
    }
    private void saveExtractedCandidate(
            Long userId,
            Long sessionId,
            Long messageId,
            AiMemoryCandidateExtractResult result
    ){
        if (!isValidExtractResult(result)) {
            return;
        }

        String memoryType = result.getMemoryType().trim().toUpperCase(Locale.ROOT);
        String memoryKey = result.getMemoryKey().trim();
        String content = result.getContent().trim();

        List<MemoryRagContext> candidateMemories = aiMemoryRetrieveService.retrieveForDecision(
                userId,
                memoryType,
                content
        );
        AiMemoryDecisionResult decision = aiMemoryDecisionService.decide(result, candidateMemories);
        String candidateAction = normalizeDecisionAction(decision.getAction());
        Long targetMemoryId = decision.getTargetMemoryId();
        String mergedContent = ACTION_MERGE.equals(candidateAction) ? decision.getMergedContent() : null;


        LocalDateTime now = LocalDateTime.now();

        AiUserMemoryCandidate existing = findExistingPendingCandidate(
                userId,
                memoryType,
                memoryKey,
                candidateAction,
                targetMemoryId
        );

        if (existing != null) {
            existing.setSessionId(sessionId);
            existing.setMessageId(messageId);
            existing.setContent(content);
            existing.setCandidateAction(candidateAction);
            existing.setReason(result.getReason());
            existing.setDecisionReason(decision.getReason());
            existing.setMergedContent(mergedContent);
            existing.setConfidence(normalizeConfidence(result.getConfidence()));
            existing.setImportance(normalizeImportance(result.getImportance()));
            existing.setUpdatedAt(now);
            existing.setTargetMemoryId(targetMemoryId);

            aiUserMemoryCandidateMapper.updateById(existing);

            return;
        }

        AiUserMemoryCandidate candidate = new AiUserMemoryCandidate();
        candidate.setUserId(userId);
        candidate.setSessionId(sessionId);
        candidate.setMessageId(messageId);
        candidate.setMemoryType(memoryType);
        candidate.setMemoryKey(memoryKey);
        candidate.setContent(content);
        candidate.setCandidateAction(candidateAction);
        candidate.setReason(result.getReason());
        candidate.setDecisionReason(decision.getReason());
        candidate.setMergedContent(mergedContent);
        candidate.setSource(SOURCE_AI_EXTRACTED);
        candidate.setConfidence(normalizeConfidence(result.getConfidence()));
        candidate.setImportance(normalizeImportance(result.getImportance()));
        candidate.setStatus(STATUS_PENDING);
        candidate.setCreatedAt(now);
        candidate.setUpdatedAt(now);
        candidate.setTargetMemoryId(targetMemoryId);

        aiUserMemoryCandidateMapper.insert(candidate);
    }
    private AiUserMemoryCandidate findExistingPendingCandidate(
            Long userId,
            String memoryType,
            String memoryKey,
            String candidateAction,
            Long targetMemoryId
    ) {
        LambdaQueryWrapper<AiUserMemoryCandidate> wrapper = new LambdaQueryWrapper<AiUserMemoryCandidate>()
                .eq(AiUserMemoryCandidate::getUserId, userId)
                .eq(AiUserMemoryCandidate::getStatus, STATUS_PENDING);

        if ((ACTION_UPDATE.equals(candidateAction) || ACTION_MERGE.equals(candidateAction))
                && targetMemoryId != null) {
            wrapper.eq(AiUserMemoryCandidate::getTargetMemoryId, targetMemoryId);
        } else {
            wrapper.eq(AiUserMemoryCandidate::getMemoryType, memoryType)
                    .eq(AiUserMemoryCandidate::getMemoryKey, memoryKey);
        }

        wrapper.orderByDesc(AiUserMemoryCandidate::getUpdatedAt)
                .last("LIMIT 1");

        return aiUserMemoryCandidateMapper.selectOne(wrapper);
    }
    //校验和工具方法
    private boolean isValidExtractResult(AiMemoryCandidateExtractResult result){
        if (result == null) {
            return false;
        }
        if(!isAllowedMemoryType(result.getMemoryType())){
            return false;
        }

        return result.getMemoryKey() != null && !result.getMemoryKey().isBlank()
                && result.getContent() != null && !result.getContent().isBlank();
    }
    private boolean isAllowedMemoryType(String memoryType) {
        if (memoryType == null) {
            return false;
        }
        String normalized = memoryType.trim().toUpperCase(Locale.ROOT);
        return "PROFILE".equals(normalized)
                || "PREFERENCE".equals(normalized)
                || "PROJECT_STATE".equals(normalized);
    }
    private String normalizeDecisionAction(String action){
        if (action == null) {
            return ACTION_CREATE;
        }
        String normalized = action.trim().toUpperCase(Locale.ROOT);
        if (ACTION_UPDATE.equals(normalized)
                || ACTION_MERGE.equals(normalized)
                || ACTION_IGNORE.equals(normalized)) {
            return normalized;
        }
        return ACTION_CREATE;
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
    private String limitText(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }
}
