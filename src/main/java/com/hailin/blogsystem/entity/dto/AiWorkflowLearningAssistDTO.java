package com.hailin.blogsystem.entity.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
public class AiWorkflowLearningAssistDTO {

    private Long conversationId;  //关联的 AI 会话 ID，可为空
    private Long planId;  //目标学习计划 ID（入口已确认属于当前用户且 ACTIVE）；歧义时为 null
    private String request;  //用户原始难点诉求（原文直通，不信任 LLM 提取的结构化字段）
    //V4③ 第二刀：建议卡确认时带的「为什么建议走这个流程」（suggestion.reason）。
    // 只走 confirm 入口，作为拆解的弱参考注入 prompt；不参与计划定位（planId 权威来源仍是 applyPlanTarget）
    private String handoffReason;
    //入口点名命中多个计划时的候选列表：Handler 先追问用户选哪个，再开始攻坚
    private List<Candidate> candidates;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Candidate {
        private Long id;
        private String title;
    }
}
