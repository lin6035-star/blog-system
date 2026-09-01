package com.hailin.blogsystem.ai.agent;

import com.hailin.blogsystem.ai.memory.AiEpisodicMemoryRetrieveService;
import com.hailin.blogsystem.ai.memory.AiMemoryRetrieveService;
import com.hailin.blogsystem.ai.rag.ArticleRagSearchService;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import com.hailin.blogsystem.entity.dto.ArticleRagSearchResult;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.EpisodicMemoryRagContext;
import com.hailin.blogsystem.entity.dto.MemoryRagContext;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.entity.vo.LearningPlansDetailVO;
import com.hailin.blogsystem.service.LearningPlansService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Learning Agent 只读动作执行器（V1）。
 *
 * 每个 action 只调对应的 Service，返回独立 observation 文本：
 * - QUERY_LEARNING_DASHBOARD -> LearningPlansService（不整包复用 AiLearningDashboardTool，
 *   避免把记忆偷偷带进计划观察）
 * - QUERY_MEMORY             -> AiMemoryRetrieveService + AiEpisodicMemoryRetrieveService
 * - SEARCH_RAG               -> ArticleRagSearchService
 *
 * 观察输出为纯文本，便于裁剪后进入下一轮决策 prompt。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LearningAgentActionExecutorImpl implements LearningAgentActionExecutor {

    private static final int TITLE_MAX = 40;
    private static final int TEXT_MAX = 200;

    private final LearningPlansService learningPlansService;
    private final AiMemoryRetrieveService aiMemoryRetrieveService;
    private final AiEpisodicMemoryRetrieveService aiEpisodicMemoryRetrieveService;
    private final ArticleRagSearchService articleRagSearchService;
    private final BlogAiProperties blogAiProperties;

    @Override
    public String execute(AgentStepDecision decision, Long userId, PageContextDTO pageContext) {
        if (userId == null) {
            return "当前未登录，无法查询学习信息。";
        }
        return switch (decision.actionType()) {
            case QUERY_LEARNING_DASHBOARD -> queryLearningDashboard(decision, userId);
            case QUERY_MEMORY -> queryMemory(decision, userId);
            case SEARCH_RAG -> searchRag(decision);
            case QUERY_ARTICLE ->
                    throw new UnsupportedOperationException("文章域动作不经过学习域执行器");
            case ASK_USER, FINAL_ANSWER, SUGGEST_WORKFLOW, SUGGEST_WRITE ->
                    throw new UnsupportedOperationException("终态动作不经过执行器");
        };
    }

    /**
     * 学习计划总览 / 详情。
     *
     * 多计划语义（与 LearningPlansServiceImpl 的“并列=歧义需追问”一致）：
     * - 点名命中唯一计划 → 返回该计划详情
     * - 点名命中多个计划 → 只列候选，不猜（Agent 下一步应 ASK_USER）
     * - 未点名且只有一个 ACTIVE → 直接返回该计划详情
     * - 未点名且多个 ACTIVE / 无 ACTIVE → 只列候选 / 总览
     */
    private String queryLearningDashboard(AgentStepDecision decision, Long userId) {
        String planRef = text(decision.input(), "planRef");

        List<LearningPlans> plans = learningPlansService.listByUser(userId);
        if (plans.isEmpty()) {
            return "当前没有任何学习计划。";
        }

        List<LearningPlans> activePlans = plans.stream()
                .filter(plan -> LearningPlans.STATUS_ACTIVE.equals(plan.getStatus()))
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("学习计划总览：共 ").append(plans.size())
                .append(" 个计划，").append(activePlans.size()).append(" 个进行中。\n");

        // 点名：唯一命中 → 详情；多个命中 → 候选（歧义，不猜）
        if (planRef != null && !planRef.isBlank()) {
            List<LearningPlans> matched =
                    learningPlansService.matchActivePlansByMessage(userId, planRef);
            if (matched.size() == 1) {
                appendPlanDetail(sb, matched.get(0), userId);
                return sb.toString();
            }
            if (matched.size() > 1) {
                sb.append("匹配到多个计划，请确认具体是哪个：\n");
                appendPlanCandidates(sb, matched);
                return sb.toString();
            }
            // 点名未命中 → 只列候选提示，不再 fallback 到唯一 ACTIVE。
            // 否则“想学 RocketMQ”会拿到《Redis 计划》详情，误导下一轮 Agent。
            sb.append("没有找到与「").append(planRef).append("」匹配的进行中计划。\n");
            appendPlanCandidates(sb, activePlans.isEmpty() ? plans : activePlans);
            return sb.toString();
        }

        // 未点名：唯一 ACTIVE → 详情；否则只列候选
        if (activePlans.size() == 1) {
            appendPlanDetail(sb, activePlans.get(0), userId);
            return sb.toString();
        }

        appendPlanCandidates(sb, plans);
        return sb.toString();
    }

    private void appendPlanCandidates(StringBuilder sb, List<LearningPlans> plans) {
        int index = 1;
        for (LearningPlans plan : plans) {
            sb.append(index++).append(". 《").append(limit(plan.getTitle(), TITLE_MAX))
                    .append("》状态=").append(plan.getStatus()).append('\n');
        }
    }

    /**
     * 用户记忆检索：语义记忆 + 情景记忆，按检索词召回。
     */
    private String queryMemory(AgentStepDecision decision, Long userId) {
        String question = text(decision.input(), "question");
        String keyword = text(decision.input(), "keyword");
        String query = question != null ? question : keyword;
        if (query == null || query.isBlank()) {
            query = "学习偏好";
        }

        StringBuilder sb = new StringBuilder("记忆摘要：\n");

        try {
            List<MemoryRagContext> memories = aiMemoryRetrieveService.retrieve(userId, query);
            for (MemoryRagContext memory : memories) {
                sb.append("- 语义记忆(").append(memory.memoryType()).append(")：")
                        .append(limit(memory.content(), TEXT_MAX)).append('\n');
            }
        } catch (Exception e) {
            log.warn("Agent 语义记忆检索失败，userId={}", userId, e);
            sb.append("- 语义记忆检索失败\n");
        }

        try {
            List<EpisodicMemoryRagContext> memories = aiEpisodicMemoryRetrieveService.retrieveForPrompt(
                    userId, blogAiProperties.getProjectKey(), query
            );
            for (EpisodicMemoryRagContext memory : memories) {
                sb.append("- 情景记忆(").append(memory.memoryType()).append(")：")
                        .append(limit(memory.content(), TEXT_MAX)).append('\n');
            }
        } catch (Exception e) {
            log.warn("Agent 情景记忆检索失败，userId={}", userId, e);
            sb.append("- 情景记忆检索失败\n");
        }

        return sb.toString();
    }

    /**
     * 站内文章知识检索（复用现有混合检索 + rerank 链路）。
     */
    private String searchRag(AgentStepDecision decision) {
        String keyword = text(decision.input(), "keyword");
        if (keyword == null || keyword.isBlank()) {
            return "站内检索缺少关键词，无法检索。";
        }

        AiIntent intent = new AiIntent();
        intent.setIntent("ARTICLE_SEARCH");
        intent.setKeyWord(keyword);

        ArticleRagSearchResult result = articleRagSearchService.search(keyword, intent);
        if (result.contexts() == null || result.contexts().isEmpty()) {
            return "站内没有检索到与「" + keyword + "」相关的文章知识。";
        }

        StringBuilder sb = new StringBuilder("站内文章知识检索结果（")
                .append(result.strategy()).append("）：\n");
        int index = 1;
        for (ArticleRagContext context : result.contexts()) {
            sb.append(index++).append(". 《").append(limit(context.title(), TITLE_MAX))
                    .append("》(").append(context.articleId()).append(")：")
                    .append(limit(context.content(), TEXT_MAX)).append('\n');
        }
        return sb.toString();
    }

    private void appendPlanDetail(StringBuilder sb, LearningPlans plan, Long userId) {
        sb.append("目标计划《").append(limit(plan.getTitle(), TITLE_MAX))
                .append("》状态=").append(plan.getStatus()).append('\n');

        LearningPlansDetailVO detail;
        try {
            detail = learningPlansService.getDetail(plan.getId(), userId);
        } catch (Exception e) {
            log.warn("Agent 读取计划详情失败，planId={}", plan.getId(), e);
            return;
        }
        if (detail == null) {
            return;
        }

        sb.append("总进度：").append(detail.getDoneTasks()).append('/')
                .append(detail.getTotalTasks()).append('\n');
        if (detail.getStages() == null) {
            return;
        }
        for (LearningPlansDetailVO.StageProgress stage : detail.getStages()) {
            sb.append("- 阶段《").append(limit(stage.getTitle(), TITLE_MAX)).append("》：");
            if (stage.getTasks() == null || stage.getTasks().isEmpty()) {
                sb.append("（无任务）\n");
                continue;
            }
            for (LearningPlansDetailVO.TaskItem task : stage.getTasks()) {
                sb.append(task.isDone() ? "[x] " : "[ ] ")
                        .append(limit(task.getTitle(), TITLE_MAX)).append("；");
            }
            sb.append('\n');
        }
    }

    private String text(Map<String, Object> input, String key) {
        if (input == null) {
            return null;
        }
        Object value = input.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private String limit(String text, int max) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
