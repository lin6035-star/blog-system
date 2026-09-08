package com.hailin.blogsystem.ai.agent;

import java.util.regex.Pattern;

/**
 * Agent 思考摘要闸门（V3.10）。
 *
 * 分级闸门，零修补：模型 thought 输出不修复不替换，按泄露强度一票否决：
 * 1. 强信号（单命中即弃）——业务 thought 不会出现的绝对机制痕迹：
 *    动作枚举（QUERY_xxx / SEARCH_RAG / SUGGEST_xxx / FINAL_ANSWER / ASK_USER）、
 *    json / 白名单 / 决策器 / 第 N 步；
 * 2. 组合信号——技术业务常见词（API/工具/模型/参数/接口）单独出现放行，
 *    与机制动词（调用/返回/输出）同现才弃（如「我调用了 XX 的 API 看返回」）；
 * 3. 通过 → trim + 截断 100 字；空输入 → null。
 *
 * 为什么不修补：thought 是可选展示字段，丢得起——回退 message 模板至少通顺；
 * 修补制造病句的风险大于保留价值（如「我调用 QUERY_MEMORY 工具查询你的记忆」
 * 修补成「我查询记忆查询你的记忆」）。命中即弃是对「模型没按 thought 用途输出」
 * 的合理惩罚，展示质量优先。
 *
 * 分级原因（防误伤业务内容）：站内是技术博客，「AI 工具怎么用 / API 设计 /
 * 模型对比」是正常话题，模型 thought 自然带这些词——单独出现不是泄露，
 * 与机制动词组合才是（如「我调用 XX 的 API」）。
 */
public final class AgentThoughtSanitizer {

    private static final int MAX_THOUGHT_CHARS = 100;

    private static final Pattern STRONG_SIGNAL = Pattern.compile(
            "(?i)(QUERY_[A-Z_]+|SEARCH_RAG|SUGGEST_[A-Z_]+|FINAL_ANSWER|ASK_USER"
                    + "|json|白名单|决策器|第 ?\\d+ ?步)");

    private static final Pattern WEAK_WORD = Pattern.compile("(?i)(api|工具|模型|参数|接口)");

    private static final Pattern MECH_VERB = Pattern.compile("(调用|返回|输出)");

    private AgentThoughtSanitizer() {
    }

    /**
     * 清洗模型 thought：违规整句置 null，干净句 trim + 截断。
     */
    public static String sanitize(String thought) {
        if (thought == null || thought.isBlank()) {
            return null;
        }
        if (STRONG_SIGNAL.matcher(thought).find()) {
            return null;
        }
        if (WEAK_WORD.matcher(thought).find() && MECH_VERB.matcher(thought).find()) {
            return null;
        }
        String trimmed = thought.trim();
        return trimmed.length() > MAX_THOUGHT_CHARS
                ? trimmed.substring(0, MAX_THOUGHT_CHARS)
                : trimmed;
    }
}
