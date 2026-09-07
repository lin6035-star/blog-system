package com.hailin.blogsystem.ai.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent Runtime 路由注册表（V3.5）：intent → runtime + fallbackMessage 单点登记。
 *
 * 收口前：AiMessageServiceImpl 主分发与 fallback 分发各一份逐字复制的 if 链
 * （AGENT 兜底 = else 一律 learning，新 intent 漏配会静默掉进学习域），
 * 兜底文案双份 6 处字符串，Planner 判定名单与执行侧 intent 字符串双写。
 * 收口后：intent 名与兜底文案只在此登记一次。
 *
 * 只登记、不决策（Codex 评审定稿约束，演化边界写死）：
 * - 只提供 resolve / fallbackMessage / intent 名单这类只读查询；
 * - 判定逻辑（如 needsThinking 语义）、业务分流（如游客门）、默认兜底策略
 *   一律留在调用方（Planner / AiMessageServiceImpl），不许往本类长——
 *   否则它从「收口」变成「新抽象」。
 *
 * 演化：加第 4 个 AGENT 域 = 本类构造加一行登记 + 新 runtime + 调用方新分支；
 * 未登记的 intent resolve 返回 null，由调用方显式处理（warn + 明确兜底），绝不静默。
 */
@Component
@Slf4j
public class AgentRuntimeRouteRegistry {

    /** 一条路由：intent 名 → runtime + 兜底文案（文案与路由同源，防改 intent 漏改文案） */
    public record AgentRoute(String intent, AgentRuntime runtime, String fallbackMessage) {
    }

    private final Map<String, AgentRoute> routesByIntent;
    private final Set<String> learningAgentIntents;
    private final Set<String> articleAgentIntents;

    public AgentRuntimeRouteRegistry(
            LearningAgentRuntime learningAgentRuntime,
            ArticleAgentRuntime articleAgentRuntime,
            GeneralAgentRuntime generalAgentRuntime
    ) {
        this.routesByIntent = List.of(
                new AgentRoute("LEARNING_AGENT", learningAgentRuntime, "暂时无法整理学习建议，请稍后重试。"),
                new AgentRoute("ARTICLE_AGENT", articleAgentRuntime, "暂时无法分析文章，请稍后重试。"),
                new AgentRoute("GENERAL_CHAT", generalAgentRuntime, "暂时无法结合你的情况回答，请稍后重试。")
        ).stream().collect(Collectors.toUnmodifiableMap(AgentRoute::intent, route -> route));
        // 判定名单按 runtime 身份派生（不重复写 intent 名；通用域名单含判定逻辑，留在 Planner）
        this.learningAgentIntents = intentsFor(LearningAgentRuntime.class);
        this.articleAgentIntents = intentsFor(ArticleAgentRuntime.class);
    }

    /** 按 intent 查 runtime；未登记返回 null（调用方显式处理，绝不静默兜底） */
    public AgentRuntime resolve(String intent) {
        AgentRoute route = routeOf(intent);
        return route == null ? null : route.runtime();
    }

    /** 按 intent 查兜底文案（与路由同源单份）；未登记返回 null */
    public String fallbackMessage(String intent) {
        AgentRoute route = routeOf(intent);
        return route == null ? null : route.fallbackMessage();
    }

    /** 全部 AGENT 域 intent 名单（调用方判定名单的单点来源） */
    public Set<String> agentIntents() {
        return routesByIntent.keySet();
    }

    /** 学习域 AGENT intent 名单（Planner isLearningAgentIntent 查它） */
    public Set<String> learningAgentIntents() {
        return learningAgentIntents;
    }

    /** 文章域 AGENT intent 名单（Planner isArticleAgentIntent 查它） */
    public Set<String> articleAgentIntents() {
        return articleAgentIntents;
    }

    private AgentRoute routeOf(String intent) {
        return intent == null ? null : routesByIntent.get(intent);
    }

    private Set<String> intentsFor(Class<? extends AgentRuntime> runtimeType) {
        return routesByIntent.values().stream()
                .filter(route -> runtimeType.isInstance(route.runtime()))
                .map(AgentRoute::intent)
                .collect(Collectors.toUnmodifiableSet());
    }
}
