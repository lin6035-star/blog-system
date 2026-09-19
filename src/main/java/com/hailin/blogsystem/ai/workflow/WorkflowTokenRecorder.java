package com.hailin.blogsystem.ai.workflow;

import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import org.springframework.stereotype.Component;

/**
 * token 用量记录：**run 级**累加到 run 内存对象，**步骤级**暂存到 ThreadLocal 缓冲。
 *
 * run 级不做 SQL 直累——否则会被后续 updateById（持有旧内存值）覆盖回 0。
 * workflow 单线程串行推进，内存累加安全；并发保护由幂等锁保证（Redis 深化阶段补）。
 *
 * 步骤级缓冲的用途：{@link WorkflowStepRunner} 只有 workflowRunId、拿不到 run 对象，
 * 而 usage 在更内层的 LlmStreamResult 里。让 accumulate 顺手暂存本步用量，
 * runner 在每步结束时取走即可写进 step 日志——4 个 handler 的 6 处调用点零改动。
 *
 * 用 ThreadLocal 而不是实例字段：recorder 是单例，实例字段会被并发 workflow 互相覆盖。
 *
 * 只做统计记录，不做计费扣减（钱包账务层后续单独设计）。
 */
@Component
public class WorkflowTokenRecorder {

    /** 本步暂存（见类注释）。取走后即 remove，防跨步/跨请求泄漏。 */
    private final ThreadLocal<TokenUsageAccumulator> stepBuffer =
            ThreadLocal.withInitial(TokenUsageAccumulator::new);

    public void accumulate(AiWorkflowRun run, TokenUsageAccumulator usage) {
        if (run == null || usage == null || usage.getTotalTokens() == 0) {
            return;
        }
        run.setInputTokens(nullSafe(run.getInputTokens()) + usage.getPromptTokens());
        run.setOutputTokens(nullSafe(run.getOutputTokens()) + usage.getCompletionTokens());
        run.setTotalTokens(nullSafe(run.getTotalTokens()) + usage.getTotalTokens());
        // 同时暂存本步用量，供 WorkflowStepRunner 落到 step 日志
        stepBuffer.get().add(usage);
    }

    /**
     * 取走本步暂存的用量并清空。
     *
     * 调用方**必须每步都调**——哪怕这一步没有 LLM 调用、哪怕不落库（runId 为 null）：
     * 不清空的话，上一步的残留会算到下一步头上。
     */
    public TokenUsageAccumulator drainStepBuffer() {
        TokenUsageAccumulator buffered = stepBuffer.get();
        stepBuffer.remove();
        return buffered;
    }

    private int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }
}
