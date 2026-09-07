package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.agent.AgentRuntimeRouteRegistry;
import com.hailin.blogsystem.ai.agent.ArticleAgentRuntime;
import com.hailin.blogsystem.ai.agent.GeneralAgentRuntime;
import com.hailin.blogsystem.ai.agent.LearningAgentRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Agent Runtime 路由注册表测试（V3.5）。
 * 只登记不决策：resolve / fallbackMessage / intent 名单查询；未登记返回 null（调用方显式兜底）。
 */
class AgentRuntimeRouteRegistryTests {

    private LearningAgentRuntime learningRuntime;
    private ArticleAgentRuntime articleRuntime;
    private GeneralAgentRuntime generalRuntime;
    private AgentRuntimeRouteRegistry registry;

    @BeforeEach
    void setUp() {
        learningRuntime = mock(LearningAgentRuntime.class);
        articleRuntime = mock(ArticleAgentRuntime.class);
        generalRuntime = mock(GeneralAgentRuntime.class);
        registry = new AgentRuntimeRouteRegistry(learningRuntime, articleRuntime, generalRuntime);
    }

    @Test
    void resolvesEachAgentIntentToItsRuntime() {
        assertThat(registry.resolve("LEARNING_AGENT")).isSameAs(learningRuntime);
        assertThat(registry.resolve("ARTICLE_AGENT")).isSameAs(articleRuntime);
        assertThat(registry.resolve("GENERAL_CHAT")).isSameAs(generalRuntime);
    }

    @Test
    void fallbackMessageComesFromSameRoute() {
        // 文案与路由同源：三域各有单份兜底（原文案逐字保留，V3.5 纯重构行为零变化）
        assertThat(registry.fallbackMessage("LEARNING_AGENT"))
                .isEqualTo("暂时无法整理学习建议，请稍后重试。");
        assertThat(registry.fallbackMessage("ARTICLE_AGENT"))
                .isEqualTo("暂时无法分析文章，请稍后重试。");
        assertThat(registry.fallbackMessage("GENERAL_CHAT"))
                .isEqualTo("暂时无法结合你的情况回答，请稍后重试。");
    }

    @Test
    void unresolvedIntentReturnsNull() {
        // 未登记 intent → null：调用方显式处理（warn + 明确兜底），注册表绝不静默给默认
        assertThat(registry.resolve("NOT_A_ROUTE")).isNull();
        assertThat(registry.fallbackMessage("NOT_A_ROUTE")).isNull();
        assertThat(registry.resolve(null)).isNull();
    }

    @Test
    void agentIntentListsAreDerivedFromRegisteredRoutes() {
        assertThat(registry.agentIntents())
                .containsExactlyInAnyOrder("LEARNING_AGENT", "ARTICLE_AGENT", "GENERAL_CHAT");
        // 判定名单按 runtime 身份派生（不重复写 intent 名）
        assertThat(registry.learningAgentIntents()).containsExactly("LEARNING_AGENT");
        assertThat(registry.articleAgentIntents()).containsExactly("ARTICLE_AGENT");
    }
}
