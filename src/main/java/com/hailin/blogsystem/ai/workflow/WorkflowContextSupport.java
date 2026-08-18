package com.hailin.blogsystem.ai.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.dto.AiWorkflowConfirmationType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class WorkflowContextSupport {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final ObjectMapper objectMapper;

    public Map<String, Object> parseContext(String contextJson) {
        if (contextJson == null || contextJson.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(contextJson, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Workflow上下文格式错误");
        }
    }

    public String toJson(Map<String, Object> context) {
        try {
            return objectMapper.writeValueAsString(context);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Workflow上下文序列化失败");
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getMap(Map<String, Object> context, String key) {
        Object value = context.get(key);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        Map<String, Object> empty = new HashMap<>();
        context.put(key, empty);
        return empty;
    }

    public Map<String, Object> getStepResults(Map<String, Object> context) {
        return getMap(context, "stepResults");
    }

    /**
     * 读取步骤结果：stepResults 优先，context 顶层兜底。
     * 兜底用于兼容历史 run（早期版本把 outline/draft/qualityCheck 直接放在 context 顶层）。
     */
    public Object getResult(Map<String, Object> context, String key) {
        Map<String, Object> stepResults = getStepResults(context);
        if (stepResults.containsKey(key)) {
            return stepResults.get(key);
        }
        return context.get(key);
    }

    public String getResultString(Map<String, Object> context, String key) {
        Object value = getResult(context, key);
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getResultMap(Map<String, Object> context, String key) {
        Object value = getResult(context, key);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return new HashMap<>();
    }

    /**
     * 写入确认卡片：Workflow 停在等待确认态时调用。
     * type 决定前端渲染哪种确认面板（见 AiWorkflowConfirmationType），
     * step 记录停在哪一步（恢复/排查用），question 仅追问类面板需要。
     */
    public void putConfirmation(
            Map<String, Object> context,
            AiWorkflowConfirmationType type,
            String step,
            String question
    ) {
        Map<String, Object> confirmation = new HashMap<>();
        confirmation.put("type", type.name());
        confirmation.put("step", step);
        if (question != null && !question.isBlank()) {
            confirmation.put("question", question);
        }
        context.put("confirmation", confirmation);
    }

    /** 清除确认卡片：确认已被消费（如填充编辑器后直接 COMPLETED），避免前端继续渲染确认面板 */
    public void clearConfirmation(Map<String, Object> context) {
        context.remove("confirmation");
    }

    public void appendFeedback(
            Map<String, Object> context,
            String step,
            String status,
            String userFeedback
    ) {
        Object value = context.get("feedbackHistory");
        List<Map<String, Object>> history;

        if (value instanceof List<?> list) {
            history = (List<Map<String, Object>>) list;
        } else {
            history = new ArrayList<>();
            context.put("feedbackHistory", history);
        }

        Map<String, Object> item = new HashMap<>();
        item.put("time", LocalDateTime.now().format(TIME_FORMAT));
        item.put("step", step);
        item.put("status", status);
        item.put("userFeedback", userFeedback);

        history.add(item);
    }

    public String normalizeRequired(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    public boolean isBlank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }

    public void touch(AiWorkflowRun run) {
        run.setUpdatedAt(LocalDateTime.now());
    }
}
