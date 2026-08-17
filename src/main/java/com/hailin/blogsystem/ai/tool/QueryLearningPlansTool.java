package com.hailin.blogsystem.ai.tool;

import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.vo.LearningPlansDetailVO;
import com.hailin.blogsystem.service.LearningPlansService;
import com.hailin.blogsystem.utils.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 学习计划列表查询工具（手写 ToolCallback 而非 @Tool 注解）。
 * 原因一：Spring AI 1.0.0 的 MethodToolCallback 对空 toolInput 直接 Assert 失败
 * （"toolInput cannot be null or empty"），无参工具被 LLM 以空 arguments 调用时
 * 会炸掉整个聊天流。手写实现绕过该断言，空输入照常执行。
 * 原因二：工具执行在 Spring AI 内部线程池，ThreadLocal 的 UserContext 拿不到，
 * userId 由调用方经 ToolContext 显式传入（不让 LLM 传，防越权查他人计划）。
 */
@Slf4j
@Component
public class QueryLearningPlansTool implements ToolCallback {

    private static final String SCHEMA = """
            {"type":"object","properties":{},"required":[]}""";

    private final LearningPlansService learningPlansService;

    public QueryLearningPlansTool(LearningPlansService learningPlansService) {
        this.learningPlansService = learningPlansService;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("queryLearningPlans")
                .description("查询当前用户的全部学习计划概要（标题、目标、进度、状态）。用户询问自己有几个学习计划、都有哪些计划、某个计划的进度概览时调用。")
                .inputSchema(SCHEMA)
                .build();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        Long userId = extractUserId(toolContext);
        if (userId == null) {
            return "当前未登录，无法查询学习计划。";
        }
        log.info("AI工具调用：queryLearningPlans, userId={}", userId);

        List<LearningPlans> plans = learningPlansService.listByUser(userId);
        if (plans == null || plans.isEmpty()) {
            return "当前用户还没有任何学习计划。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("当前用户共有 ").append(plans.size()).append(" 个学习计划：\n\n");

        int index = 1;
        for (LearningPlans plan : plans) {
            LearningPlansDetailVO detail = learningPlansService.getDetail(plan.getId(), userId);
            sb.append(index++).append(". 《").append(plan.getTitle()).append("》")
                    .append(statusLabel(plan.getStatus()))
                    .append("（计划ID：").append(plan.getId()).append("）\n");
            if (plan.getGoal() != null && !plan.getGoal().isBlank()) {
                sb.append("   目标：").append(plan.getGoal()).append("\n");
            }
            sb.append("   进度：").append(detail.getDoneTasks()).append("/").append(detail.getTotalTasks())
                    .append(" 项任务已完成，共 ").append(detail.getStages().size()).append(" 个阶段\n\n");
        }

        sb.append("请基于以上真实数据回答用户，不要编造列表外的计划。");
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
