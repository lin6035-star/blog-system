package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.entity.AiConversationSummaries;
import com.hailin.blogsystem.entity.AiMessages;
import com.hailin.blogsystem.entity.vo.AiConversationSummaryStatusVO;
import com.hailin.blogsystem.mapper.AiConversationSummaryMapper;
import com.hailin.blogsystem.mapper.AiMessageMapper;
import com.hailin.blogsystem.service.AiConversationSummaryService;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiConversationSummaryServiceImpl extends ServiceImpl<AiConversationSummaryMapper, AiConversationSummaries>
        implements AiConversationSummaryService {

    private static final int RECENT_WINDOW = 40;
    private static final int COMPRESS_BATCH_SIZE = 20;
    private static final int MESSAGE_TEXT_LIMIT = 500;

    private final AiMessageMapper aiMessageMapper;
    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;

    @Override
    @Async("conversationSummaryExecutor")
    public void compressAfterChat(Long userId,Long sessionId){
        if (userId == null || sessionId == null) {
            return;
        }

        try{
            doCompress(userId,sessionId);
        } catch (Exception e) {
            log.warn("Conversation Summary 压缩失败，userId={}, sessionId={}", userId, sessionId, e);
        }
    }


    @Override
    public String buildSummaryPrompt(Long userId,Long sessionId){
        if (userId == null || sessionId == null) {
            return "";
        }

        AiConversationSummaries summary = lambdaQuery()
                .eq(AiConversationSummaries::getUserId, userId)
                .eq(AiConversationSummaries::getSessionId, sessionId)
                .one();

        if (summary == null || summary.getSummary() == null || summary.getSummary().isBlank()) {
            return "";
        }

        return toPromptText(summary);
    }

    private void doCompress(Long userId,Long sessionId){
        AiConversationSummaries summary = findSummary(sessionId);

        Long coveredUntilMessageId = summary == null || summary.getCoveredUntilMessageId() == null
                ? 0L
                : summary.getCoveredUntilMessageId();

        List<AiMessages> batch = listCompressBatch(sessionId,coveredUntilMessageId);
        if(batch.size() < COMPRESS_BATCH_SIZE){
            return;
        }

        // 抢占压缩状态行：首次压缩无行时插入 compressing=1 的占位行，
        // 已有行时通过 AND compressing=0 条件锁互斥；拿不到锁说明已有压缩在跑，跳过本次
        summary = acquireCompressingSummary(userId, sessionId, summary);
        if (summary == null) {
            return;
        }

        String oldSummaryJson = summary.getSummaryJson();
        if (oldSummaryJson == null || oldSummaryJson.isBlank()) {
            oldSummaryJson = defaultSummaryJson();
        }

        Long newCoveredUntilMessageId = batch.get(batch.size() - 1).getId();
        int newCoveredMessageCount = (summary.getCoveredMessageCount() == null ? 0 : summary.getCoveredMessageCount())
                + batch.size();

        try {
            String newSummaryJson = compressByLlm(oldSummaryJson, batch);
            String newSummaryText = jsonToCompactMarkdown(newSummaryJson);

            LocalDateTime now = LocalDateTime.now();
            int updated = baseMapper.updateWithVersion(
                    sessionId,
                    summary.getVersion(),
                    newSummaryText,
                    newSummaryJson,
                    newCoveredUntilMessageId,
                    newCoveredMessageCount,
                    now
            );

            if (updated == 0) {
                log.info("Conversation Summary 乐观锁未命中，跳过本次更新，sessionId={}", sessionId);
                return;
            }

            log.info("Conversation Summary 压缩完成，sessionId={}, coveredUntil={}, batchSize={}",
                    sessionId, newCoveredUntilMessageId, batch.size());
        } finally {
            // 乐观锁未命中或 LLM 异常时也要恢复空闲态，避免 compressing 卡死
            baseMapper.clearCompressing(sessionId, LocalDateTime.now());
        }
    }

    /**
     * 抢占压缩状态：无行时先插入 compressing=1 的占位行（首次压缩标记必须落库，前端才能抓到"正在压缩"），
     * 有行时用 AND compressing=0 条件锁互斥，避免并发重复压缩。
     */
    private AiConversationSummaries acquireCompressingSummary(
            Long userId,
            Long sessionId,
            AiConversationSummaries summary
    ) {
        LocalDateTime now = LocalDateTime.now();

        if (summary == null) {
            AiConversationSummaries placeholder = new AiConversationSummaries();
            placeholder.setUserId(userId);
            placeholder.setSessionId(sessionId);
            placeholder.setSummary("");
            placeholder.setSummaryJson(defaultSummaryJson());
            placeholder.setCoveredUntilMessageId(0L);
            placeholder.setCoveredMessageCount(0);
            placeholder.setVersion(1);
            placeholder.setCompressing(true);
            placeholder.setCreatedAt(now);
            placeholder.setUpdatedAt(now);

            try {
                save(placeholder);
                return placeholder;
            } catch (DuplicateKeyException e) {
                summary = findSummary(sessionId);
                if (summary == null) {
                    return null;
                }
            }
        }

        int locked = baseMapper.tryMarkCompressing(sessionId, now);
        if (locked == 0) {
            log.info("Conversation Summary 已有压缩任务执行中，跳过本次触发，sessionId={}", sessionId);
            return null;
        }

        return summary;
    }

    @Override
    public AiConversationSummaryStatusVO getSummaryStatus(Long sessionId) {
        Long userId = UserContext.get();
        if (userId == null || sessionId == null) {
            return new AiConversationSummaryStatusVO(false, null, 0);
        }

        AiConversationSummaries summary = lambdaQuery()
                .eq(AiConversationSummaries::getUserId, userId)
                .eq(AiConversationSummaries::getSessionId, sessionId)
                .one();

        if (summary == null) {
            return new AiConversationSummaryStatusVO(false, null, 0);
        }

        return new AiConversationSummaryStatusVO(
                Boolean.TRUE.equals(summary.getCompressing()),
                summary.getLastCompressedAt(),
                summary.getCoveredMessageCount() == null ? 0 : summary.getCoveredMessageCount()
        );
    }


    private AiConversationSummaries findSummary(Long sessionId) {
        return lambdaQuery()
                .eq(AiConversationSummaries::getSessionId, sessionId)
                .one();
    }

    @Override
    public void deleteBySession(Long userId, Long sessionId) {
        if (userId == null || sessionId == null) {
            return;
        }

        remove(new LambdaQueryWrapper<AiConversationSummaries>()
                .eq(AiConversationSummaries::getUserId, userId)
                .eq(AiConversationSummaries::getSessionId, sessionId));
    }


    private List<AiMessages> listCompressBatch(Long sessionId, Long coveredUntilMessageId) {
        AiMessages recentBoundary = findRecentBoundary(sessionId);
        if (recentBoundary == null || recentBoundary.getId() == null) {
            return List.of();
        }

        return aiMessageMapper.selectList(new LambdaQueryWrapper<AiMessages>()
                .eq(AiMessages::getSessionId, sessionId)
                .gt(AiMessages::getId, coveredUntilMessageId)
                .lt(AiMessages::getId, recentBoundary.getId())
                .orderByAsc(AiMessages::getCreatedAt)
                .last("LIMIT " + COMPRESS_BATCH_SIZE));
    }

    private AiMessages findRecentBoundary(Long sessionId) {
        List<AiMessages> recent = aiMessageMapper.selectList(new LambdaQueryWrapper<AiMessages>()
                .eq(AiMessages::getSessionId, sessionId)
                .orderByDesc(AiMessages::getCreatedAt)
                .last("LIMIT " + RECENT_WINDOW));

        if (recent == null || recent.size() < RECENT_WINDOW) {
            return null;
        }

        Collections.reverse(recent);
        return recent.get(0);
    }

    private String compressByLlm(String oldSummaryJson, List<AiMessages> messages) {
        String response = chatClientBuilder.build()
                .prompt()
                .system("""
                        你是一个会话滚动摘要压缩器。
                        你的任务是把旧 summary 和新增对话消息合并成新的结构化 summary。

                        规则：
                        - 只保留对后续对话有帮助的信息，不要记录流水账。
                        - 保留当前主题、用户目标、已做决定、已完成事项、重要约束、待办、未解决问题。
                        - 每个数组最多 5 条。
                        - 过时、重复、已经被后续内容覆盖的信息要合并或删除。
                        - Workflow 过程日志、进度提示、重复状态可以忽略或压缩成一句话。
                        - Workflow 的最终结果、用户确认、失败原因、下一步动作需要保留。
                        - 如果没有实质内容，也要返回合法 JSON，topic 可以是“无实质内容”。
                        - 只能返回 JSON，不要 Markdown，不要解释。

                        JSON 格式：
                        {
                          "topic": "",
                          "goal": "",
                          "decisions": [],
                          "completed": [],
                          "constraints": [],
                          "pendingTasks": [],
                          "unresolvedIssues": []
                        }
                        """)
                .user("""
                        旧 summary：
                        %s

                        新增对话消息：
                        %s
                        """.formatted(
                        oldSummaryJson == null || oldSummaryJson.isBlank() ? defaultSummaryJson() : oldSummaryJson,
                        formatMessages(messages)
                ))
                .call()
                .content();

        return normalizeSummaryJson(response);
    }

    private String formatMessages(List<AiMessages> messages) {
        StringBuilder sb = new StringBuilder();

        for (AiMessages message : messages) {
            String role = "user".equals(message.getRole()) ? "用户" : "AI";
            sb.append("messageId=").append(message.getId()).append("\n")
                    .append("createdAt=").append(message.getCreatedAt()).append("\n")
                    .append("workflowRunId=").append(message.getWorkflowRunId()).append("\n")
                    .append(role).append("：")
                    .append(limitText(message.getContent(), MESSAGE_TEXT_LIMIT))
                    .append("\n\n");
        }

        return sb.toString();
    }

    private String normalizeSummaryJson(String response) {
        if (response == null || response.isBlank()) {
            return defaultSummaryJson();
        }

        String json = extractJsonObject(response);
        if (json == null || json.isBlank()) {
            return defaultSummaryJson();
        }

        try {
            JsonNode node = objectMapper.readTree(json);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            log.warn("Conversation Summary JSON 解析失败，response={}", response, e);
            return defaultSummaryJson();
        }
    }

    private String extractJsonObject(String response) {
        int start = response.indexOf("{");
        int end = response.lastIndexOf("}");

        if (start < 0 || end < start) {
            return null;
        }

        return response.substring(start, end + 1);
    }

    private String toPromptText(AiConversationSummaries summary) {
        if (summary.getSummary() != null && !summary.getSummary().isBlank()) {
            return "## 对话摘要\n" + summary.getSummary().trim();
        }

        return "";
    }

    private String jsonToCompactMarkdown(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);

            StringBuilder sb = new StringBuilder();
            appendLine(sb, "主题", text(node, "topic"));
            appendLine(sb, "目标", text(node, "goal"));
            appendArray(sb, "已做决定", node.get("decisions"));
            appendArray(sb, "已完成事项", node.get("completed"));
            appendArray(sb, "重要约束", node.get("constraints"));
            appendArray(sb, "待办", node.get("pendingTasks"));
            appendArray(sb, "未解决问题", node.get("unresolvedIssues"));

            String text = sb.toString().trim();
            return text.isBlank() ? "无实质内容" : text;
        } catch (Exception e) {
            return "无实质内容";
        }
    }

    private void appendLine(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append(label).append("：").append(value).append("\n");
    }

    private void appendArray(StringBuilder sb, String title, JsonNode array) {
        if (array == null || !array.isArray() || array.isEmpty()) {
            return;
        }

        sb.append(title).append("：").append("\n");

        int count = 0;
        for (JsonNode item : array) {
            if (count >= 5) {
                break;
            }
            String text = item.asText("");
            if (!text.isBlank()) {
                sb.append("- ").append(text).append("\n");
                count++;
            }
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.get(field) == null) {
            return "";
        }
        return node.get(field).asText("");
    }

    private String defaultSummaryJson() {
        return """
                {"topic":"","goal":"","decisions":[],"completed":[],"constraints":[],"pendingTasks":[],"unresolvedIssues":[]}
                """;
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
