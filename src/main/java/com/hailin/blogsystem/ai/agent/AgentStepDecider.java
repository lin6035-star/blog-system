package com.hailin.blogsystem.ai.agent;

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

    AgentStepDecision decide(
            String goal,
            String clippedContext,
            int stepNo,
            int maxSteps
    );

    /**
     * maxSteps 到顶后，从已有观察生成最终回答。
     *
     * 返回 null 表示总结失败，由 Runtime 降级为拼接 observation。
     * 默认实现返回 null（纯 mock 决策器不需要总结能力）。
     */
    default String summarize(String goal, String clippedContext) {
        return null;
    }
}
