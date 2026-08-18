package com.hailin.blogsystem.ai.workflow;

import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import org.springframework.stereotype.Component;

/**
 * run 级 token 用量记录：累加到 run 内存对象，由 Service 层统一 updateById 落库。
 * 不做 SQL 直累——否则会被后续 updateById（持有旧内存值）覆盖回 0。
 * workflow 单线程串行推进，内存累加安全；并发保护由幂等锁保证（Redis 深化阶段补）。
 * 只做统计记录，不做计费扣减（钱包账务层后续单独设计）。
 */
@Component
public class WorkflowTokenRecorder {

    public void accumulate(AiWorkflowRun run, TokenUsageAccumulator usage) {
        if (run == null || usage == null || usage.getTotalTokens() == 0) {
            return;
        }
        run.setInputTokens(nullSafe(run.getInputTokens()) + usage.getPromptTokens());
        run.setOutputTokens(nullSafe(run.getOutputTokens()) + usage.getCompletionTokens());
        run.setTotalTokens(nullSafe(run.getTotalTokens()) + usage.getTotalTokens());
    }

    private int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }
}
