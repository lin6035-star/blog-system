package com.hailin.blogsystem.component;

import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.constants.BlogConstants;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 预扣上限与钱包余额下界的启动校验。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §2①
 *
 * <p><b>为什么需要它</b>：user_wallet 的 CHECK 下界（{@link BlogConstants.Wallet#BALANCE_FLOOR}）
 * 是给「符号写反 / 扣减跑飞」这类 bug 兜底的，不是给正常业务用的。但余额确实允许透支一轮
 * ——预扣门槛是 {@code balance > 0} 而不是 {@code balance >= reserved}，
 * 所以一次正常预扣就会把余额扣成负数。
 *
 * <p>两边一旦脱钩（有人把某个 bizType 的预扣调得过大），<b>正常请求</b>会被 CHECK 约束拒绝，
 * 而报错信息是「约束冲突」，完全指不到根因。所以在这里把关系锁死，启动即失败。
 */
@Component
@RequiredArgsConstructor
public class BillingReserveLimitValidator {

    private static final Logger log = LoggerFactory.getLogger(BillingReserveLimitValidator.class);

    /**
     * 安全系数：预扣上限 × 它仍要小于 |下界|。
     *
     * <p>留 2 倍而不是 1 倍，是因为下界还要容纳<b>并发预扣叠加</b>——
     * 同一用户开多个会话时，每次预扣的门槛都只看自己的那一笔（balance &gt; 0），
     * 余额可能被连续扣成更深的负数。
     */
    private static final long SAFETY_FACTOR = 2L;

    private final BlogAiProperties blogAiProperties;

    @PostConstruct
    public void validate() {
        Map<String, Long> limits = blogAiProperties.getBilling().getMaxReservePerBizType();
        if (limits.isEmpty()) {
            log.info("[计费上限] 未配置 max-reserve-per-biz-type，跳过校验（AI 计费未接入时不阻塞启动）");
            return;
        }

        long max = limits.values().stream().mapToLong(Long::longValue).max().orElse(0L);
        long floor = Math.abs(BlogConstants.Wallet.BALANCE_FLOOR);
        long needed = max * SAFETY_FACTOR;

        if (needed >= floor) {
            throw new IllegalStateException(String.format(
                    "AI 计费预扣上限与钱包余额下界不匹配：最大预扣 %d credit × 安全系数 %d = %d，"
                            + "必须小于 user_wallet 的 CHECK 下界 %d（db/init.sql 的 chk_wallet_balance_floor）。"
                            + "否则一次正常预扣就会撞上约束冲突，而报错信息指不到根因。"
                            + "请调小 blog.ai.billing.max-reserve-per-biz-type，或调大 DDL 下界——两处必须同步。",
                    max, SAFETY_FACTOR, needed, floor));
        }

        log.info("[计费上限] 最大预扣 {} credit × {} = {} < 下界 {}，校验通过（各 bizType：{}）",
                max, SAFETY_FACTOR, needed, floor, limits);
    }
}
