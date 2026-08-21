package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.entity.AiMessages;
import com.hailin.blogsystem.entity.dto.EpisodicMemoryExtractResult;
import com.hailin.blogsystem.mapper.AiMessageMapper;
import com.hailin.blogsystem.service.AiEpisodicMemoryExtractorService;
import com.hailin.blogsystem.service.AiEpisodicMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiEpisodicMemoryExtractorServiceImpl implements
        AiEpisodicMemoryExtractorService {

    private static final int WINDOW_MESSAGE_COUNT = 20;
    private static final int WINDOW_TRIGGER_MOD = 10;

    private final AiMessageMapper aiMessageMapper;
    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;
    private final AiEpisodicMemoryService aiEpisodicMemoryService;


    @Override
    @Async("memoryCandidateTaskExecutor")
    public void extractAfterChat(
            Long userId,
            Long sessionId,
            Long userMessageId,
            Long assistantMessageId,
            String userMessage,
            String assistantReply
    ){
        if (userId == null || sessionId == null) {
            return;
        }

        boolean eventTriggered = shouldExtract(userMessage)
                || shouldExtract(assistantReply);

        long messageCount = countSessionMessages(sessionId);
        boolean windowTriggered = messageCount > 0 && messageCount % WINDOW_TRIGGER_MOD == 0;

        if (!eventTriggered && !windowTriggered) {
            return;
        }

        try {
            List<AiMessages> windowMessages = listRecentWindowMessages(sessionId);
            List<EpisodicMemoryExtractResult> results = extractByLlm(windowMessages);

            if (results.isEmpty()) {
                log.info("Episodic Memory 提取结果为空，userId={}, sessionId={}", userId, sessionId);
                return;
            }

            for (EpisodicMemoryExtractResult result : results) {
                aiEpisodicMemoryService.saveExtractedMemory(userId, sessionId, result);
            }

            log.info("Episodic Memory 提取完成，userId={}, sessionId={}, count={}",
                    userId, sessionId, results.size());
        } catch (Exception e) {
            log.warn("Episodic Memory 提取失败，userId={}, sessionId={}", userId, sessionId, e);
        }

    }

    private long countSessionMessages(Long sessionId) {
        return aiMessageMapper.selectCount(new LambdaQueryWrapper<AiMessages>()
                .eq(AiMessages::getSessionId, sessionId));
    }

    private List<AiMessages> listRecentWindowMessages(Long sessionId) {
        List<AiMessages> messages = aiMessageMapper.selectList(new LambdaQueryWrapper<AiMessages>()
                .eq(AiMessages::getSessionId, sessionId)
                .orderByDesc(AiMessages::getCreatedAt)
                .last("LIMIT " + WINDOW_MESSAGE_COUNT));

        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<AiMessages> ordered = new ArrayList<>(messages);
        Collections.reverse(ordered);
        return ordered;
    }

    private boolean shouldExtract(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }

        String text = message.replaceAll("\\s+", "");

        return containsAny(text,
                "决定",
                "选择",
                "最终",
                "拍板",
                "就用",
                "完成",
                "已完成",
                "测试通过",
                "通过了",
                "上线",
                "部署",
                "里程碑",
                "阶段",
                "进度",
                "下一步",
                "准备",
                "进入"
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

    private List<EpisodicMemoryExtractResult> extractByLlm(List<AiMessages> windowMessages) {
        if (windowMessages == null || windowMessages.isEmpty()) {
            return List.of();
        }

        String response = chatClientBuilder.build()
                .prompt()
                .system("""
                        你是一个情景记忆提取器。
                        你的任务是从一段用户与 AI 的历史对话窗口中，提取值得长期保存的历史事件。

                        情景记忆只回答：过去发生了什么、当时为什么这样决定、后续计划是什么。

                        重要来源规则：
                        - 只要是用户明确表达过的项目阶段、事件、里程碑、决定、确认、确定、计划、完成情况，都可以保存。
                        - AI 单方面提出的建议、方案、分析、总结，不要保存。
                        - 如果只有 AI 建议，没有用户确认，但如果用户后面明确采纳了，有用户“可以 / 就这样 / 按这个做 / 最终决定 / 我来改 / 继续 / 好，那就用这个”，就可以保存最终版本
                        - 阶段推进、完成节点，优先记为 MILESTONE。
                        - 当前状态、进行中的事项，优先记为 EVENT。
                        - 后续要做的事，记为 PLAN。
                        - 明确拍板选型，记为 DECISION。
        
                        只允许提取四类：
                        - DECISION：必须有用户明确拍板、选择、决定、确认。
                        - PLAN：必须有用户明确表示后续要做、准备做、下一步做。
                        - MILESTONE：必须是用户或系统明确完成了某件事。
                        - EVENT：必须是已经发生的重要事实，不能只是 AI 建议。

                        严格规则：
                        - 不要保存普通问答、闲聊、寒暄、简单报错。
                        - 不要提取用户画像、长期偏好、稳定事实；这些属于 Semantic/User Memory。
                        - 不要保存原始聊天流水，要压缩成一条可读历史事件。
                        - content 必须包含足够上下文，尤其是决策原因。
                        - 如果没有值得保存的事件，返回空数组 []。
                        - shouldRemember=false 的对象不要返回，直接返回空数组或只返回 shouldRemember=true 的对象。
                        - memoryType 只能是 DECISION、EVENT、MILESTONE、PLAN。
                        - importance 范围 1 到 10；低于 6 通常不值得保存。
                        - confidence 范围 0 到 1；低于 0.6 通常不值得保存。
                        - 只能返回 JSON 数组，不要返回 Markdown，不要解释。

                        JSON 格式：
                        [
                          {
                            "shouldRemember": true,
                            "memoryType": "DECISION",
                            "title": "RAG 选用 ES",
                            "content": "用户和 AI 讨论 RAG 方案后，最终决定使用 ES，因为需要同时支持 Keyword Search 和 Vector Search。",
                            "importance": 8,
                            "confidence": 0.91,
                            "sourceMessageIds": [123, 124, 125],
                            "occurredAt": "2026-08-17T21:30:00"
                          }
                        ]
                        """)
                .user("""
                        对话窗口：
                        %s
                        """.formatted(formatWindowMessages(windowMessages)))
                .call()
                .content();

        return parseExtractResult(response);
    }

    private String formatWindowMessages(List<AiMessages> messages) {
        StringBuilder sb = new StringBuilder();

        for (AiMessages message : messages) {
            String role = "user".equals(message.getRole()) ? "用户" : "AI";
            sb.append("messageId=").append(message.getId()).append("\n")
                    .append("createdAt=").append(message.getCreatedAt()).append("\n")
                    .append(role).append("：")
                    .append(limitText(message.getContent(), 1200))
                    .append("\n\n");
        }

        return sb.toString();
    }

    List<EpisodicMemoryExtractResult> parseExtractResult(String response) {
        if (response == null || response.isBlank()) {
            return List.of();
        }

        String json = extractJsonArray(response);
        if (json == null || json.isBlank()) {
            return List.of();
        }

        try {
            return objectMapper.readValue(json, new TypeReference<List<EpisodicMemoryExtractResult>>() {});
        } catch (Exception e) {
            log.warn("Episodic Memory JSON 解析失败，response={}", response, e);
            return List.of();
        }
    }

    String extractJsonArray(String response) {
        int start = response.indexOf("[");
        int end = response.lastIndexOf("]");

        if (start < 0 || end < start) {
            return null;
        }

        return response.substring(start, end + 1);
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
