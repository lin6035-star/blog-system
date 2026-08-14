package com.hailin.blogsystem.ai.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.entity.AiWorkflowRun;
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
