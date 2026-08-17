package com.hailin.blogsystem.ai.tool;

import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.vo.LearningPlansDetailVO;
import com.hailin.blogsystem.service.LearningPlansService;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 学习计划详情查询工具（普通聊天 Tool Calling）。
 * 列表工具见 QueryLearningPlansTool（手写 ToolCallback，绕开 Spring AI 无参工具的空断言 bug）。
 * userId 经 ToolContext 传入（工具执行线程读不到 ThreadLocal），不让 LLM 传 userId，防止越权查他人计划。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AiLearningPlanTools {

    private final LearningPlansService learningPlansService;

    @Tool(description = "查询当前用户某个学习计划的完整详情（目标、全部阶段、每个阶段的任务及完成状态）。用户询问某个具体计划学到哪了、某个阶段有什么任务时调用。")
    public String queryLearningPlanDetail(
            @ToolParam(description = "计划标题关键词（如 RocketMQ、Java），或 queryLearningPlans 返回的计划ID数字") String planRef,
            ToolContext toolContext
    ) {
        Long userId = extractUserId(toolContext);
        if (userId == null) {
            return "当前未登录，无法查询学习计划。";
        }
        if (planRef == null || planRef.isBlank()) {
            return "缺少计划标题或ID，无法查询学习计划详情。";
        }
        log.info("AI工具调用：queryLearningPlanDetail, planRef={}", planRef);

        //纯数字 → 按 ID 查（兼容 LLM 复制了计划ID的场景）
        if (planRef.matches("\\d+")) {
            try {
                return buildDetailText(learningPlansService.getDetail(Long.valueOf(planRef), userId));
            } catch (IllegalArgumentException e) {
                return "学习计划不存在或无权访问。";
            }
        }

        //标题关键词 → 在本人计划列表内匹配（归属由 userId 过滤保证，LLM 复制文字远比复制 19 位雪花 ID 可靠）
        List<LearningPlans> matched = learningPlansService.matchPlansByTitle(userId, planRef);

        if (matched.isEmpty()) {
            return "当前用户没有标题包含\"" + planRef + "\"的学习计划。请先用 queryLearningPlans 查询全部计划再回答。";
        }
        if (matched.size() > 1) {
            StringBuilder sb = new StringBuilder("找到多个标题匹配的学习计划：\n");
            for (int i = 0; i < matched.size(); i++) {
                sb.append(i + 1).append(". 《").append(matched.get(i).getTitle()).append("》\n");
            }
            sb.append("请让用户确认想看哪一个，不要擅自代替用户选择。");
            return sb.toString();
        }

        return buildDetailText(learningPlansService.getDetail(matched.get(0).getId(), userId));
    }

    private String buildDetailText(LearningPlansDetailVO detail) {
        LearningPlans plan = detail.getPlan();
        StringBuilder sb = new StringBuilder();
        sb.append("学习计划《").append(plan.getTitle()).append("》").append(statusLabel(plan.getStatus())).append("\n");
        sb.append("目标：").append(plan.getGoal() == null || plan.getGoal().isBlank() ? "无" : plan.getGoal()).append("\n");
        sb.append("总进度：").append(detail.getDoneTasks()).append("/").append(detail.getTotalTasks()).append(" 项任务已完成\n\n");

        int stageIndex = 1;
        for (LearningPlansDetailVO.StageProgress stage : detail.getStages()) {
            long done = stage.getTasks().stream().filter(LearningPlansDetailVO.TaskItem::isDone).count();
            sb.append("阶段").append(stageIndex++).append("：").append(stage.getTitle())
                    .append("（").append(done).append("/").append(stage.getTasks().size()).append("）\n");
            int taskIndex = 1;
            for (LearningPlansDetailVO.TaskItem task : stage.getTasks()) {
                sb.append("  ").append(task.isDone() ? "[x]" : "[ ]")
                        .append(" ").append(taskIndex++).append(". ").append(task.getTitle()).append("\n");
            }
            sb.append("\n");
        }

        sb.append("请基于以上真实数据回答用户，不要编造计划外的任务。");
        return sb.toString();
    }

    //userId 优先从 ToolContext 取（工具执行线程读不到 ThreadLocal），ThreadLocal 只作直接调用的兜底
    private Long extractUserId(ToolContext toolContext) {
        Object value = toolContext == null ? null : toolContext.getContext().get("userId");
        if (value instanceof Number number) {
            return number.longValue();
        }
        return UserContext.get();
    }

    private String statusLabel(String status) {
        if (LearningPlans.STATUS_ACTIVE.equals(status)) {
            return "（进行中）";
        }
        if (LearningPlans.STATUS_COMPLETED.equals(status)) {
            return "（已完成）";
        }
        if (LearningPlans.STATUS_ARCHIVED.equals(status)) {
            return "（已归档）";
        }
        return "";
    }
}
