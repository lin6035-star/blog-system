package com.hailin.blogsystem.ai.workflow;

import com.hailin.blogsystem.entity.dto.AiWorkflowStatus;
import com.hailin.blogsystem.entity.dto.AiWorkflowStep;
import org.springframework.stereotype.Component;

/**
 * 状态基础设施：无歧义的状态谓词、解析与展示文案。
 * 不含各 Workflow 的状态机规则——哪个状态能 approve / reject / retry 由各 Handler 的 switch 自己决定。
 */
@Component
public class WorkflowStatusSupport {

    //终态：COMPLETED / CANCELLED / FAILED
    public boolean isFinished(String status) {
        return AiWorkflowStatus.COMPLETED.name().equals(status)
                || AiWorkflowStatus.CANCELLED.name().equals(status)
                || AiWorkflowStatus.FAILED.name().equals(status);
    }

    //等待用户操作的状态（WAITING_ 前缀）
    public boolean isWaiting(String status) {
        return status != null && status.startsWith("WAITING_");
    }

    public AiWorkflowStatus parseStatus(String status) {
        try {
            return AiWorkflowStatus.valueOf(status);
        } catch (Exception e) {
            throw new IllegalArgumentException("Workflow状态异常");
        }
    }

    public AiWorkflowStep parseStep(String step) {
        try {
            return AiWorkflowStep.valueOf(step);
        } catch (Exception e) {
            throw new IllegalArgumentException("Workflow步骤异常");
        }
    }

    public String statusLabel(String status) {
        if (status == null) return "未知";
        switch (status) {
            case "RUNNING": return "执行中";
            case "WAITING_REQUIREMENT_CONFIRM": return "等待补充需求";
            case "WAITING_OUTLINE_CONFIRM": return "等待确认大纲";
            case "WAITING_PLAN_CONFIRM": return "等待确认优化方案";
            case "WAITING_LEARNING_PLAN_CONFIRM": return "学习计划已生成，等待用户确认或修改";
            case "WAITING_DRAFT_CONFIRM": return "等待确认草稿";
            case "WAITING_FILL_CONFIRM": return "等待确认填充";
            case "WAITING_USER_SAVE": return "等待用户保存/发布";
            case "PAUSED": return "已暂停";
            case "COMPLETED": return "已完成";
            case "FAILED": return "执行失败";
            case "CANCELLED": return "已取消";
            default: return status;
        }
    }

    public String stepLabel(String step) {
        if (step == null) return "未知";
        switch (step) {
            case "REQUIREMENT_ANALYZE": return "需求分析";
            case "MEMORY_RETRIEVE": return "记忆召回";
            case "RAG_SEARCH": return "知识库检索";
            case "GENERATE_OUTLINE": return "生成大纲";
            case "GENERATE_DRAFT": return "生成草稿";
            case "QUALITY_CHECK": return "质量检查";
            case "FILL_ARTICLE": return "填充编辑器";
            case "LOAD_ARTICLE": return "加载文章";
            case "ANALYZE_ARTICLE": return "分析文章";
            case "GENERATE_OPTIMIZATION_PLAN": return "生成优化方案";
            case "REWRITE_ARTICLE": return "重写文章";
            case "CONTENT_CHECK": return "内容检查";
            case "ANALYZE_GOAL": return "分析学习目标";
            case "GENERATE_PLAN": return "生成结构化学习计划";
            case "SAVE_PLAN": return "保存学习计划";
            case "LOAD_PLAN": return "加载学习计划";
            case "ANALYZE_CHANGE": return "分析调整诉求";
            case "LOCATE_STAGE": return "定位难点阶段";
            case "GENERATE_TASKS": return "拆解任务点";
            case "APPEND_TASKS": return "追加任务点";
            default: return step;
        }
    }
}
