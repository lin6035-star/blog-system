package com.hailin.blogsystem.entity.vo;

import com.hailin.blogsystem.entity.AiEditorCommand;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class AiWorkflowRunVO {

    private String id;
    private String workflowType;
    private String workflowVersion;
    private String status;
    private String currentStep;
    /**
     * Workflow 上下文。
     *
     * 这里直接返回结构化对象，前端不需要再手动解析 JSON。
     */
    private Map<String, Object> context;
    private AiEditorCommand editorAction;
    private String pauseReason;
    private String errorMessage;

    public static AiWorkflowRunVO from(AiWorkflowRun run, ObjectMapper objectMapper) {
        AiWorkflowRunVO vo = new AiWorkflowRunVO();
        vo.setId(String.valueOf(run.getId()));
        vo.setWorkflowType(run.getWorkflowType());
        vo.setWorkflowVersion(run.getWorkflowVersion());
        vo.setStatus(run.getStatus());
        vo.setCurrentStep(run.getCurrentStep());
        vo.setContext(parseContext(run.getContextJson(), objectMapper));
        vo.setPauseReason(run.getPauseReason());
        vo.setErrorMessage(run.getErrorMessage());
        return vo;
    }

    private static Map<String, Object> parseContext(String contextJson, ObjectMapper objectMapper) {
        if (contextJson == null || contextJson.isBlank()) {
            return new HashMap<>();
        }

        try {
            return objectMapper.readValue(contextJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }
}
