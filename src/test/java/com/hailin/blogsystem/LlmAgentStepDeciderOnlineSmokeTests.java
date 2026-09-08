package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.agent.AgentStepDecision;
import com.hailin.blogsystem.ai.agent.AgentThoughtSanitizer;
import com.hailin.blogsystem.ai.agent.ArticleAgentStepDecider;
import com.hailin.blogsystem.ai.agent.GeneralAgentStepDecider;
import com.hailin.blogsystem.ai.agent.LlmAgentStepDecider;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V3.10 三域真实 LLM 决策 smoke（设计稿 §六）。
 *
 * 本刀改了 LlmAgentStepDecider.buildUserPrompt 的输出约束（多了 thought 字段要求），
 * 真实风险是决策 JSON 更容易变形（解析失败修复一次仍失败 → run FAILED）。
 * 评测门不经过 AgentStepDecider，所以这里补三域各 1 条真实 LLM 决策调用：
 * - actionType ∈ 对应域白名单 + decision 非 null（锁「thought 要求不破坏决策 JSON」）
 * - thought 可有可无；非 null 时必须是已清洗态（AgentThoughtSanitizer 幂等）
 *
 * 跑法：-Dgroups=eval -DrunRealLlm=true（在线门一组；不依赖 ES，VectorStore 已 mock）
 */
@Tag("eval")
@SpringBootTest
@EnabledIfSystemProperty(named = "runRealLlm", matches = "true")
class LlmAgentStepDeciderOnlineSmokeTests {

    @Autowired
    private LlmAgentStepDecider llmAgentStepDecider;

    @Autowired
    private GeneralAgentStepDecider generalAgentStepDecider;

    @Autowired
    private ArticleAgentStepDecider articleAgentStepDecider;

    /** mock 掉真 ES vectorStore（decider 本身不碰 ES，只为不拖 Spring 上下文） */
    @MockBean
    private VectorStore vectorStore;

    private static final Set<AgentStepActionType> LEARNING_ALLOWED = Set.of(
            AgentStepActionType.QUERY_LEARNING_DASHBOARD,
            AgentStepActionType.QUERY_MEMORY,
            AgentStepActionType.SEARCH_RAG,
            AgentStepActionType.ASK_USER,
            AgentStepActionType.FINAL_ANSWER,
            AgentStepActionType.SUGGEST_WORKFLOW,
            AgentStepActionType.SUGGEST_WRITE
    );

    private static final Set<AgentStepActionType> GENERAL_ALLOWED = Set.of(
            AgentStepActionType.QUERY_MEMORY,
            AgentStepActionType.SEARCH_RAG,
            AgentStepActionType.ASK_USER,
            AgentStepActionType.FINAL_ANSWER
    );

    private static final Set<AgentStepActionType> ARTICLE_ALLOWED = Set.of(
            AgentStepActionType.QUERY_ARTICLE,
            AgentStepActionType.QUERY_MEMORY,
            AgentStepActionType.SEARCH_RAG,
            AgentStepActionType.ASK_USER,
            AgentStepActionType.FINAL_ANSWER,
            AgentStepActionType.SUGGEST_WORKFLOW,
            AgentStepActionType.SUGGEST_WRITE
    );

    @Test
    void learningDomainDecisionSurvivesThoughtRequirement() {
        AgentStepDecision decision = llmAgentStepDecider.decide(
                "帮我安排今天学什么 Redis", "（无）", 1, 5);
        assertDecisionOk(decision, LEARNING_ALLOWED);
    }

    @Test
    void generalDomainDecisionSurvivesThoughtRequirement() {
        AgentStepDecision decision = generalAgentStepDecider.decide(
                "结合我最近的情况，给个学习建议", "（无）", 1, 3);
        assertDecisionOk(decision, GENERAL_ALLOWED);
    }

    @Test
    void articleDomainDecisionSurvivesThoughtRequirement() {
        AgentStepDecision decision = articleAgentStepDecider.decide(
                "帮我分析这篇文章的结构", "（无）", 1, 5);
        assertDecisionOk(decision, ARTICLE_ALLOWED);
    }

    private void assertDecisionOk(AgentStepDecision decision, Set<AgentStepActionType> allowed) {
        // JSON 解析成功 + 白名单内（thought 字段要求不破坏决策输出）
        assertThat(decision).isNotNull();
        assertThat(decision.actionType()).isIn(allowed);
        // thought 可有可无；非 null 必须是已清洗态（无机制泄露，闸门幂等）
        String thoughtSummary = decision.thoughtSummary();
        if (thoughtSummary != null) {
            assertThat(AgentThoughtSanitizer.sanitize(thoughtSummary))
                    .as("thoughtSummary 必须已是清洗态，不应残留机制词：%s", thoughtSummary)
                    .isEqualTo(thoughtSummary);
        }
    }
}
