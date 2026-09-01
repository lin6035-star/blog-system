package com.hailin.blogsystem.entity.vo;

import com.hailin.blogsystem.entity.AiMessages;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiMessageVO {
    private String id;
    private String sessionId;
    private String workflowRunId;
    private String agentRunId;  //关联的 Agent Run ID（V2.1 建议卡快照恢复用）
    private String role;  //user or assistant
    private String content;
    private String pageContext;
    private LocalDateTime createdAt;


    public static AiMessageVO from(AiMessages aiMessages) {
        AiMessageVO aiMessageVO = new AiMessageVO();
        aiMessageVO.setId(String.valueOf(aiMessages.getId()));
        aiMessageVO.setSessionId(String.valueOf(aiMessages.getSessionId()));
        if (aiMessages.getWorkflowRunId() != null) {
            aiMessageVO.setWorkflowRunId(String.valueOf(aiMessages.getWorkflowRunId()));
        }
        if (aiMessages.getAgentRunId() != null) {
            aiMessageVO.setAgentRunId(String.valueOf(aiMessages.getAgentRunId()));
        }
        aiMessageVO.setRole(aiMessages.getRole());
        aiMessageVO.setContent(aiMessages.getContent());
        aiMessageVO.setPageContext(aiMessages.getPageContext());
        aiMessageVO.setCreatedAt(aiMessages.getCreatedAt());

        return aiMessageVO;
    }
}
