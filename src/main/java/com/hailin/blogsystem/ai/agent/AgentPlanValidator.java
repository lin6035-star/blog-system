package com.hailin.blogsystem.ai.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Plan Preview 计划校验闸门（V3.13）。
 *
 * 确定性拒绝，零修补：任一规则不过 → **整条计划丢弃**（返回 null），
 * 不做「截断 / 改写 / 删掉出问题的项后保留」。字面清洗会制造「看起来正常但
 * 语义已经变了」的计划——那比没有计划更糟，因为用户会当真（对齐 V3.10
 * AgentThoughtSanitizer 的零修补原则）。
 *
 * 规则：
 * 1. 数量 2~4（< 2 不是多目标；> 4 展示过重）
 * 2. 单项非空
 * 3. 去重（规范化：trim + 统一小写后比对）
 * 4. 单项 ≤ 60 字（超长说明在写段落，不是写目标）
 * 5. 总长 ≤ 200 字（展示空间）
 * 6. 机制词命中即整条丢弃——只拦动作枚举与实现标识；`API` / `工具` / `模型` /
 *    `参数` / `接口` 等可能属于文章主题的普通词不单独判违规（同上，防误伤业务内容）
 */
public final class AgentPlanValidator {

    public static final int MIN_ITEMS = 2;
    public static final int MAX_ITEMS = 4;

    private static final int MAX_ITEM_CHARS = 60;
    private static final int MAX_TOTAL_CHARS = 200;

    private static final Pattern MECH_SIGNAL = Pattern.compile(
            "(?i)(QUERY_[A-Z_]+|SEARCH_RAG|SUGGEST_[A-Z_]+|FINAL_ANSWER|ASK_USER"
                    + "|json|prompt|白名单|决策器|第 ?\\d+ ?步)");

    private AgentPlanValidator() {
    }

    /**
     * 校验模型输出的计划。
     *
     * @return 通过 → 规范化（trim 后）的列表；任一规则不过 → null
     */
    public static List<String> validate(List<String> plan) {
        if (plan == null || plan.size() < MIN_ITEMS || plan.size() > MAX_ITEMS) {
            return null;
        }

        List<String> normalized = new ArrayList<>(plan.size());
        Set<String> seen = new HashSet<>();
        int totalChars = 0;

        for (String item : plan) {
            if (item == null || item.isBlank()) {
                return null;
            }
            String trimmed = item.trim();
            if (trimmed.length() > MAX_ITEM_CHARS) {
                return null;
            }
            if (MECH_SIGNAL.matcher(trimmed).find()) {
                return null;
            }
            if (!seen.add(trimmed.toLowerCase(Locale.ROOT))) {
                return null;
            }
            totalChars += trimmed.length();
            normalized.add(trimmed);
        }

        return totalChars > MAX_TOTAL_CHARS ? null : normalized;
    }
}
