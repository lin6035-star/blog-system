package com.hailin.blogsystem.ai.agent;

import com.hailin.blogsystem.ai.TokenUsageAccumulator;

/**
 * Agent 步骤决策器。
 *
 * 输入：目标、已裁剪的 observation 列表、当前步号、最大步数。
 * 输出：下一步动作。
 *
 * V1 只读语义：决策器只能从白名单动作中选，
 * 后端（LearningAgentRuntime）负责最终校验与执行。
 */
public interface AgentStepDecider {

    /**
     * 决策下一步动作。
     *
     * @param usage 出参：本次决策的 LLM 用量累加到这里，**含 JSON 解析失败后的 repair 重试**。
     *              之所以用出参而不是塞进返回值——{@link AgentStepDecision} 是三域共享的 record，
     *              加字段会波及所有构造点；出参只影响本接口的 2 个调用点。
     *              允许为 null（不需要统计的调用方，如单测里的 mock 决策器）。
     */
    AgentStepDecision decide(
            String goal,
            String clippedContext,
            int stepNo,
            int maxSteps,
            TokenUsageAccumulator usage
    );

    /**
     * maxSteps 到顶后，从已有观察生成最终回答。
     *
     * 返回 null 表示总结失败，由 Runtime 降级为拼接 observation。
     * 默认实现返回 null（纯 mock 决策器不需要总结能力）。
     *
     * @param usage 同 {@link #decide}：出参，允许为 null。
     */
    default String summarize(String goal, String clippedContext, TokenUsageAccumulator usage) {
        return null;
    }
}
