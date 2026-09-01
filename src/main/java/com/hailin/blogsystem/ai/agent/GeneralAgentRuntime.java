package com.hailin.blogsystem.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.mapper.AiAgentStepMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 通用域 Agent Runtime（V3 通用思考模式）。
 *
 * 继承 AbstractAgentRuntime 公共循环骨架，本类只保留通用域差异：
 * - 白名单：QUERY_MEMORY / SEARCH_RAG + 通用终态（无 SUGGEST_WORKFLOW / SUGGEST_WRITE，
 *   通用域没有可建议的域内 Workflow，也不做写提案）
 * - maxSteps=3（比学习/文章域收紧，配合「能不查就不查」prompt 双闸门控延迟）
 * - 文案全部中性化（无领域语义）
 *
 * 入口：GENERAL_CHAT + needsThinking=true（分类器语义判定 → Planner 归一化 → 公共 SSE 管道）。
 */
@Service
@Slf4j
public class GeneralAgentRuntime extends AbstractAgentRuntime implements AgentRuntime {

    private static final Set<AgentStepActionType> ALLOWED_ACTIONS = Set.of(
            AgentStepActionType.QUERY_MEMORY,
            AgentStepActionType.SEARCH_RAG,
            AgentStepActionType.ASK_USER,
            AgentStepActionType.FINAL_ANSWER
    );

    private static final Set<String> ALLOWED_SUGGEST_WORKFLOW_TYPES = Set.of();

    private final AgentStepDecider decider;

    public GeneralAgentRuntime(
            GeneralAgentStepDecider decider,
            GeneralAgentActionExecutor executor,
            AiAgentRunMapper runMapper,
            AiAgentStepMapper stepMapper,
            ObjectMapper objectMapper
    ) {
        super(executor, runMapper, stepMapper, objectMapper);
        this.decider = decider;
    }

    // ==================== 领域钩子 ====================

    @Override
    protected AgentStepDecider decider() {
        return decider;
    }

    @Override
    protected Set<AgentStepActionType> allowedActions() {
        return ALLOWED_ACTIONS;
    }

    @Override
    protected Set<String> allowedSuggestWorkflowTypes() {
        return ALLOWED_SUGGEST_WORKFLOW_TYPES;
    }

    /** 通用域收紧决策步数（延迟控制：maxSteps 上限 + prompt 下限）。 */
    @Override
    protected int maxSteps() {
        return 3;
    }

    @Override
    protected String emptyAnswerFallback() {
        return "暂时无法回答你的问题，请补充一下背景或换个问法。";
    }

    @Override
    protected String emptyAskUserFallback() {
        return "你想了解或解决什么？";
    }

    @Override
    protected String defaultWorkflowSuggestionReason(String workflowType) {
        // 白名单无 SUGGEST_WORKFLOW，正常不会走到；保留中性实现兜底
        return "建议进入「" + workflowType + "」流程。";
    }

    @Override
    protected String suggestWorkflowRejectHint() {
        return "系统提示：你在没有任何查询结果时尝试建议启动 Workflow，后端已拒绝。";
    }

    @Override
    protected String emptySummaryFallback() {
        return "暂时无法整理回答，请补充一下具体问题。";
    }

    @Override
    protected String summaryHeader() {
        return "根据已有信息，整理如下：\n";
    }
}
