package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.ai.billing.BillingHandle;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.entity.vo.WalletBillEntryVO;
import com.hailin.blogsystem.service.AiBillingService;
import com.hailin.blogsystem.service.WalletService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 用户账单（聚合视角）。
 *
 * 设计：{@code WalletBillEntryVO} 的类注释
 *
 * <p>核心主张是「记账视角 ≠ 账单视角」：{@code wallet_transaction} 一次 AI 调用写两条
 * （预扣 + 退回，为了逐笔回放对账），而用户要看的是一次调用**只显示一条净额**。
 * 这几条用例把这个差异的四种情形都盖住了：已结算、预扣中、全额退、以及分页口径。
 */
@SpringBootTest
class WalletBillTests {

    @Autowired
    private WalletService walletService;

    @Autowired
    private AiBillingService aiBillingService;

    private final long userId = 9_500_000_000_000L + System.nanoTime() % 1_000_000_000L;

    @Test
    void rechargeAppearsAsSingleEntry() {
        recharge(10_000, "W-1");

        PageVO<WalletBillEntryVO> bill = walletService.listBill(userId, 1, 10);

        assertThat(bill.getTotal()).isEqualTo(1);
        WalletBillEntryVO entry = bill.getList().get(0);
        assertThat(entry.getBizType()).isEqualTo("RECHARGE_ORDER");
        assertThat(entry.getAmount()).isEqualTo(10_000);
        assertThat(entry.getBalanceAfter()).isEqualTo(10_000);
    }

    /**
     * 最要紧的一条：一次 AI 调用在账单里是**一条**净额，
     * 不是「预扣 -400」+「退回 +365」两条。
     */
    @Test
    void reserveAndRefundMergeIntoOneNetAmount() {
        recharge(10_000, "W-2");
        BillingHandle handle = aiBillingService.reserve("CHAT", "MSG-BILL-1", userId, 400);
        aiBillingService.settle(handle, usageOf(100));

        PageVO<WalletBillEntryVO> bill = walletService.listBill(userId, 1, 10);

        assertThat(bill.getTotal()).isEqualTo(2);
        assertThat(aiEntry(bill).getAmount())
                .as("净额应当等于真实消耗，而不是预扣的 400")
                .isEqualTo(-1);
    }

    /** 预扣中（还没配对退款）不进账单——净额没定下来，不是一笔已发生的事实 */
    @Test
    void inFlightReserveIsHidden() {
        recharge(10_000, "W-3");
        aiBillingService.reserve("CHAT", "MSG-BILL-2", userId, 400);

        PageVO<WalletBillEntryVO> bill = walletService.listBill(userId, 1, 10);

        assertThat(bill.getTotal()).isEqualTo(1);
        assertThat(bill.getList()).noneMatch(e -> "AI_BILLING".equals(e.getBizType()));
    }

    /** 取消的对话净额为 0：用户没花钱，账单里就没有「一笔消费」这回事 */
    @Test
    void fullyRefundedCallIsHidden() {
        recharge(10_000, "W-4");
        BillingHandle handle = aiBillingService.reserve("CHAT", "MSG-BILL-3", userId, 400);
        aiBillingService.release(handle);

        PageVO<WalletBillEntryVO> bill = walletService.listBill(userId, 1, 10);

        assertThat(bill.getTotal()).isEqualTo(1);
    }

    /** 余额快照要取**结算后**那条（AGG 里 balance_seq 最大的），不是预扣后的负数 */
    @Test
    void balanceSnapshotComesFromTheLastTransactionOfTheBusiness() {
        recharge(10_000, "W-5");
        BillingHandle handle = aiBillingService.reserve("CHAT", "MSG-BILL-4", userId, 400);
        aiBillingService.settle(handle, usageOf(100));

        PageVO<WalletBillEntryVO> bill = walletService.listBill(userId, 1, 10);

        assertThat(aiEntry(bill).getBalanceAfter())
                .isEqualTo(walletService.getWallet(userId).getBalance())
                .as("应当是结算后的余额，而不是预扣那一刻的 -390")
                .isPositive();
    }

    /**
     * 分页按**聚合后的条目**算，不是按原始流水行算。
     *
     * <p>这正是「在前端过滤」做不到的事：3 次 AI 调用在原始流水里是 6 行，
     * 前端按行分页会得到忽多忽少的一页。
     */
    @Test
    void paginationCountsAggregatedEntriesNotRawRows() {
        recharge(10_000, "W-6");
        for (int i = 0; i < 3; i++) {
            BillingHandle handle = aiBillingService.reserve("CHAT", "MSG-BILL-P" + i, userId, 400);
            aiBillingService.settle(handle, usageOf(100));
        }

        PageVO<WalletBillEntryVO> firstPage = walletService.listBill(userId, 1, 2);

        // 1 条充值 + 3 条 AI = 4 条账单（原始流水是 1 + 6 = 7 行）
        assertThat(firstPage.getTotal()).isEqualTo(4);
        assertThat(firstPage.getList()).hasSize(2);
    }

    // ---------- helpers ----------

    private void recharge(long amount, String bizId) {
        walletService.increase(userId, amount, "RECHARGE", "RECHARGE_ORDER",
                bizId, "RECHARGE:" + bizId, null);
    }

    private WalletBillEntryVO aiEntry(PageVO<WalletBillEntryVO> bill) {
        return bill.getList().stream()
                .filter(e -> "AI_BILLING".equals(e.getBizType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("账单里没有 AI_BILLING 条目：" + bill.getList()));
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
