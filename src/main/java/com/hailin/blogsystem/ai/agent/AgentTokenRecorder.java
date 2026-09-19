package com.hailin.blogsystem.ai.agent;

import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.entity.AiAgentRun;

/**
 * Agent run 级 token 用量累加工具。
 *
 * **只累加到 run 内存对象，不落库**——落库交给 run 生命周期里已有的 updateById 顺带完成。
 *
 * 为什么不逐个出口显式落库：{@code AbstractAgentRuntime.run()} 有 8 个出口
 * （finish / markFailed / suggestWorkflow / completeWithTerminalFailure / 子类终态扩展…），
 * 逐个改签名既啰嗦又容易漏。而它们**都**会 {@code updateById(run)}——
 * 把值挂在对象上就全覆盖了，出口零改动。
 *
 * 做成静态工具而不是 @Component：累加本身不需要任何依赖，
 * 而注入一个只提供静态方法的 bean 会把 AbstractAgentRuntime 的三个子类构造器
 * 和 ArticleEvidenceVerifier 的测试一起搅动。兜底落库由 AbstractAgentRuntime
 * 用它已有的 runMapper 内联完成。
 */
public final class AgentTokenRecorder {

    private AgentTokenRecorder() {
    }

    /** 累加一次 LLM 调用的用量到 run 内存对象（不落库）。null 安全。 */
    public static void accumulate(AiAgentRun run, TokenUsageAccumulator usage) {
        if (run == null || usage == null || usage.getTotalTokens() == 0) {
            return;
        }
        run.setInputTokens(nullSafe(run.getInputTokens()) + usage.getPromptTokens());
        run.setOutputTokens(nullSafe(run.getOutputTokens()) + usage.getCompletionTokens());
        run.setTotalTokens(nullSafe(run.getTotalTokens()) + usage.getTotalTokens());
    }

    private static int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }
}
