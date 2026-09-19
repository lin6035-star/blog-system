package com.hailin.blogsystem;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.ai.billing.BillingHandle;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.AiBillingOrder;
import com.hailin.blogsystem.exception.InsufficientBalanceException;
import com.hailin.blogsystem.mapper.AiBillingOrderMapper;
import com.hailin.blogsystem.service.AiBillingService;
import com.hailin.blogsystem.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AI 计费：预扣 / 结算 / 释放。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §5
 *
 * <p>三条不变量各有一组用例：
 * <ul>
 *   <li><b>预扣是上界</b>——余额不足时整个预扣事务回滚，不留孤儿单据</li>
 *   <li><b>结算只退款</b>——实际用量无论怎么算，退款额都不会是负数</li>
 *   <li><b>一张单只退一次</b>——结算与释放共用同一个流水幂等键</li>
 * </ul>
 *
 * <p>本测试不调用真实 LLM：直接构造 usage 调 settle，等价于流结束时的落库那一刻。
 */
@SpringBootTest
class AiBillingServiceTests {

    private static final long INITIAL_BALANCE = 10_000L;

    @Autowired
    private AiBillingService aiBillingService;

    @Autowired
    private WalletService walletService;

    @Autowired
    private AiBillingOrderMapper aiBillingOrderMapper;

    @Autowired
    private BlogAiProperties blogAiProperties;

    private final long userId = 9_400_000_000_000L + System.nanoTime() % 1_000_000_000L;

    @BeforeEach
    void fundWallet() {
        walletService.increase(userId, INITIAL_BALANCE, "RECHARGE", "RECHARGE_ORDER",
                "SEED", "RECHARGE:SEED", null);
    }

    // ---------- 预扣 ----------

    @Test
    void reserveDeductsWalletAndCreatesOrder() {
        BillingHandle handle = aiBillingService.reserve("CHAT", "MSG-1", userId, 500);

        assertThat(handle).isNotNull();
        assertThat(balance()).isEqualTo(INITIAL_BALANCE - 500);
        assertThat(pendingReserves()).isEqualTo(1);

        AiBillingOrder order = findOrder(handle.orderNo());
        assertThat(order.getStatus()).isEqualTo("RESERVED");
        assertThat(order.getReservedCredit()).isEqualTo(500);
        assertThat(order.getExpireAt()).isNotNull();
    }

    /**
     * 余额 ≤ 0 时预扣失败，且<b>不能留下单据</b>——否则会有一条永远没人结算的 RESERVED 单，
     * 既占着 pendingReserveCount，又会被超时任务反复捞出来处理。
     */
    @Test
    void reserveRejectsWhenBalanceIsNotPositiveAndRollsBackTheOrder() {
        drainBalance();

        assertThatThrownBy(() -> aiBillingService.reserve("CHAT", "MSG-2", userId, 100))
                .isInstanceOf(InsufficientBalanceException.class);

        assertThat(findOrderByBizId("MSG-2")).isNull();
        assertThat(pendingReserves()).isZero();
    }

    // ---------- 结算 ----------

    @Test
    void settleRefundsTheDifference() {
        BillingHandle handle = aiBillingService.reserve("CHAT", "MSG-3", userId, 500);

        aiBillingService.settle(handle, usageOf(100));

        long expectedCredit = creditOf(100);
        assertThat(balance()).isEqualTo(INITIAL_BALANCE - expectedCredit);
        assertThat(pendingReserves()).isZero();

        AiBillingOrder order = findOrder(handle.orderNo());
        assertThat(order.getStatus()).isEqualTo("SETTLED");
        assertThat(order.getActualCredit()).isEqualTo(expectedCredit);
        assertThat(order.getTotalTokens()).isEqualTo(100);
    }

    /**
     * usage 缺失时按预扣全额结算——用户确实拿到了回答。
     *
     * <p>两个错误方向都不能走：按 0 收（把"统计缺失"记成免费）和挂起等人工补录
     * （为一个边缘场景引入第四种状态）。这里同时断言"没退款"和"有结算记录"。
     */
    @Test
    void settleChargesFullReserveWhenUsageMissing() {
        BillingHandle handle = aiBillingService.reserve("CHAT", "MSG-4", userId, 500);

        aiBillingService.settle(handle, new TokenUsageAccumulator());

        assertThat(balance()).isEqualTo(INITIAL_BALANCE - 500);
        AiBillingOrder order = findOrder(handle.orderNo());
        assertThat(order.getStatus()).isEqualTo("SETTLED");
        assertThat(order.getActualCredit()).isEqualTo(500);
        assertThat(order.getTotalTokens()).isZero();
    }

    /**
     * 实际用量超出预扣 = 硬上限失效。
     *
     * <p>此时<b>不能</b>按 actual 扣——那等于绕开预扣把余额扣穿；
     * 也不能静默按 reserved 收——要留下 {@code [BILLING-OVERRUN]} 告警。
     * 这里断言的是"扣款被预扣封顶"。
     */
    @Test
    void settleCapsAtReserveWhenActualExceedsIt() {
        BillingHandle handle = aiBillingService.reserve("CHAT", "MSG-5", userId, 500);

        aiBillingService.settle(handle, usageOf(100_000));

        assertThat(balance()).isEqualTo(INITIAL_BALANCE - 500);
        assertThat(findOrder(handle.orderNo()).getActualCredit()).isEqualTo(500);
    }

    @Test
    void settleIsIdempotent() {
        BillingHandle handle = aiBillingService.reserve("CHAT", "MSG-6", userId, 500);

        aiBillingService.settle(handle, usageOf(100));
        long afterFirstSettle = balance();
        aiBillingService.settle(handle, usageOf(100));

        assertThat(balance()).isEqualTo(afterFirstSettle);
    }

    // ---------- 释放 ----------

    @Test
    void releaseRefundsEverything() {
        BillingHandle handle = aiBillingService.reserve("CHAT", "MSG-7", userId, 500);

        aiBillingService.release(handle);

        assertThat(balance()).isEqualTo(INITIAL_BALANCE);
        assertThat(pendingReserves()).isZero();
        assertThat(findOrder(handle.orderNo()).getStatus()).isEqualTo("RELEASED");
    }

    /**
     * 结算之后再释放不能退第二次钱。
     *
     * <p>真实链路上这两件事都可能发生：流的 doFinally 在正常完成时也会走到，
     * 释放分支必须靠 {@code status = 'RESERVED'} 条件更新空转掉。
     */
    @Test
    void releaseAfterSettleDoesNotRefundTwice() {
        BillingHandle handle = aiBillingService.reserve("CHAT", "MSG-8", userId, 500);
        aiBillingService.settle(handle, usageOf(100));
        long afterSettle = balance();

        aiBillingService.release(handle);

        assertThat(balance()).isEqualTo(afterSettle);
        assertThat(findOrder(handle.orderNo()).getStatus()).isEqualTo("SETTLED");
    }

    @Test
    void nullHandleIsToleratedBySettleAndRelease() {
        // 计费开关关闭时 reserve 返回 null，调用方不做判断直接往下走也不能炸
        aiBillingService.settle(null, usageOf(100));
        aiBillingService.release(null);

        assertThat(balance()).isEqualTo(INITIAL_BALANCE);
    }

    // ---------- helpers ----------

    private void drainBalance() {
        walletService.decrease(userId, INITIAL_BALANCE, "AI_RESERVE", "AI_BILLING",
                "DRAIN", "AI_RESERVE:DRAIN", null);
    }

    private long balance() {
        return walletService.getWallet(userId).getBalance();
    }

    private long pendingReserves() {
        return walletService.getWallet(userId).getPendingReserveCount();
    }

    private AiBillingOrder findOrder(String orderNo) {
        return aiBillingOrderMapper.selectOne(
                new LambdaQueryWrapper<AiBillingOrder>().eq(AiBillingOrder::getOrderNo, orderNo));
    }

    private AiBillingOrder findOrderByBizId(String bizId) {
        return aiBillingOrderMapper.selectOne(
                new LambdaQueryWrapper<AiBillingOrder>().eq(AiBillingOrder::getBizId, bizId));
    }

    /** 按配置里的单价折算——不写死 10，免得改价时测试全红 */
    private long creditOf(int tokens) {
        long per1k = blogAiProperties.getBilling().getCreditPer1kTokens();
        return ((long) tokens * per1k + 999) / 1000;
    }

    private static TokenUsageAccumulator usageOf(int totalTokens) {
        TokenUsageAccumulator accumulator = new TokenUsageAccumulator();
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(totalTokens / 2);
        when(usage.getCompletionTokens()).thenReturn(totalTokens / 2);
        when(usage.getTotalTokens()).thenReturn(totalTokens);
        accumulator.add(usage);
        return accumulator;
    }
}
