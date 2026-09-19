package com.hailin.blogsystem.ai.billing;

import com.hailin.blogsystem.config.BlogAiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 普通聊天的预扣额度估算。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §5.3
 *
 * <p><b>为什么这里可以"估"，而 P95 不行</b>：
 * 预扣必须发生在调用<b>之前</b>，而这一刻输入是<b>确定的</b>——{@code finalPromptContext}
 * 已经拼好了，它的长度可以直接数出来。所以输入侧既不需要裁、也不需要猜，
 * 按实际长度折算就行。
 *
 * <p>真正需要"上限"的是另外两项，它们都是可配置的硬边界：
 * <ul>
 *   <li><b>输出</b>：{@code maxTokens}（不设它就是不可知）</li>
 *   <li><b>工具轮数</b>：每轮都会把完整上下文重发一遍，token 成倍增长</li>
 * </ul>
 *
 * <p><b>系数取 1 字符 = 1 token</b>：中文约 1 字 1 token（接近真实），
 * 英文约 4 字符 1 token（高估约 4 倍）。宁可高估——
 * 预扣是<b>上限不是报价</b>：估高的代价只是余额少的用户被放行成负数（随后按实际结算退回），
 * 估低的代价是 {@code actual > reserved}，那意味着账算错了。
 */
@Component
@RequiredArgsConstructor
public class ChatReserveCalculator {

    /** 该路径在 max-reserve-per-biz-type 里的 key */
    public static final String BIZ_TYPE = "CHAT";

    private final BlogAiProperties blogAiProperties;

    /**
     * @param promptText 本次调用实际发送的用户侧文本（{@code AiPrompt.finalPromptContext}）
     * @return 预扣额度（credit）；结果被 {@code max-reserve-per-biz-type.CHAT} 压顶
     */
    public long estimate(String promptText) {
        BlogAiProperties.Billing billing = blogAiProperties.getBilling();

        // system prompt 不在 finalPromptContext 里（它是 ChatClient 的 defaultSystem），
        // 但同样每轮都会发送，必须计入
        long inputChars = charCount(promptText) + charCount(blogAiProperties.getSystemPrompt());
        long perRoundTokens = inputChars + billing.getChatMaxTokens();
        long totalTokens = perRoundTokens * Math.max(1, billing.getMaxToolRounds());

        long credit = ceilDiv(totalTokens * billing.getCreditPer1kTokens(), 1000L);

        // 压顶：再长的输入也不会让预扣触碰到 user_wallet 的 CHECK 下界
        // （启动时由 BillingReserveLimitValidator 保证 cap × 安全系数 < |下界|）
        Long cap = billing.getMaxReservePerBizType().get(BIZ_TYPE);
        return cap == null ? credit : Math.min(credit, cap);
    }

    private static int charCount(String text) {
        return text == null ? 0 : text.length();
    }

    /** 向上取整：整数除法会把很小的请求算成 0，那就等于免费 */
    private static long ceilDiv(long dividend, long divisor) {
        return (dividend + divisor - 1) / divisor;
    }
}
