package com.hailin.blogsystem.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 判断链模型：让「分类 / 决策 / 验证」这些**判断型**环节可以使用与生成链不同的模型。
 *
 * **解决什么**（2026-09-13 实测）：同一句话「帮我分析我的Agent学习计划，看看有没有问题」，
 * 换模型后判出完全不同的结果——一个判 GENERAL_CHAT（压根没认出来）、
 * 一个判 LEARNING_PROGRESS（直接起调整工作流让你选计划），换成 plus 才判对 LEARNING_AGENT。
 *
 * 根因不在提示词：**分类器本质是让 LLM 画一条分界线**，换模型就是换把尺子。
 * 而两条链对稳定性的要求完全不同——
 * - 生成链（大纲/草稿/计划内容）：差一点用户能接受 → 可以随免费额度换便宜的
 * - 判断链（走哪条路）：判错整条路就偏了 → 值得绑一个稳的
 * 判断链每次只吃一句话、输出几十个 token，**用强模型的成本远低于生成**。
 *
 * **怎么用**：判断型调用点把 options 交给 {@link #applyTo}——配了 judge-model 就覆盖，
 * 没配就原样返回（**默认零行为变化**，不配等于没有这个类）。
 *
 * **边界**：只接判断点，不接生成点。已接：分类器 / Agent 决策器 / 证据验证器。
 * 未接（判错不影响本轮路由）：记忆提取、对话摘要、收尾总结。
 */
@Component
@Slf4j
public class AiJudgeModelSupport {

    private final String judgeModel;

    public AiJudgeModelSupport(@Value("${blog.ai.judge-model:}") String judgeModel) {
        this.judgeModel = judgeModel == null ? "" : judgeModel.trim();
        if (!this.judgeModel.isBlank()) {
            log.info("判断链模型已配置: judgeModel={}（分类器 / Agent 决策器 / 证据验证器）", this.judgeModel);
        }
    }

    /**
     * 判断链专用：**固定 temperature=0**；配了 judge-model 时再覆盖 model。
     *
     * <p><b>为什么必须显式设 0</b>：判断型任务要的是<b>确定性</b>——同一句话必须判出同一个结果。
     * 而之前这里只设 model、不设 temperature，走的是服务端默认（OpenAI 兼容 API 默认 1.0），
     * 于是**同一个模型、同一句话也会飘**：
     * <ul>
     *   <li>压测实测：40 个并发发同一句「我想系统学 Redis」，只有 15 个判成 LEARNING_PLAN</li>
     *   <li>评测实测：37 例里 8 例失败，且失败模式高度一致（边界 case 塌陷到 GENERAL_CHAT 兜底）</li>
     * </ul>
     * 本类注释原先把这个现象归因于「换模型就是换把尺子」——模型确实是因素之一，
     * 但**同一模型内的随机性来自 temperature**，这一半当时漏了。
     *
     * <p>生成链（大纲/草稿/计划内容）**不受影响**：那些调用点不走本方法，
     * 保留默认温度，用户要的正是文字上的多样性。
     */
    public OpenAiChatOptions.Builder applyTo(OpenAiChatOptions.Builder builder) {
        builder = builder.temperature(0.0);
        return judgeModel.isBlank() ? builder : builder.model(judgeModel);
    }

    public boolean isConfigured() {
        return !judgeModel.isBlank();
    }
}
