package com.hailin.blogsystem;

import com.hailin.blogsystem.entity.WalletTransaction;
import com.hailin.blogsystem.entity.vo.WalletLedgerAnomalyVO;
import com.hailin.blogsystem.mapper.WalletTransactionMapper;
import com.hailin.blogsystem.service.WalletService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钱包流水对账。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §7.3
 *
 * <p>核心主张：账号是<b>只增不改</b>的账本，所以判据是「必须相等」而不是「最终收敛」
 * ——这一点和秒杀对账正好相反，见 {@code WalletReconcileTask} 的类注释。
 *
 * <p>测试数据分两种来源：正常路径走 {@code walletService.increase}（保证自洽），
 * 异常场景<b>直接插流水</b>——因为走 service 根本造不出坏数据，而坏数据正是要检测的对象。
 * 每个用例用独立的 userId（实例字段 + nanoTime），互不干扰。
 */
@SpringBootTest
class WalletReconcileTests {

    /**
     * 全表查询的结果里只挑自己那几行：测试库中其他用例的数据也在同一张表里。
     *
     * <p>取值必须<b>远大于整个测试套件造出的流水总量</b>，否则自己的行会被 {@code LIMIT}
     * 挤到窗口外，表现为「单跑绿、全套红」——查错了方向的那种失败。
     */
    private static final int SCAN_LIMIT = 100_000;

    @Autowired
    private WalletService walletService;

    @Autowired
    private WalletTransactionMapper walletTransactionMapper;

    private final long userId = 9_600_000_000_000L + System.nanoTime() % 1_000_000_000L;

    @Test
    void healthyLedgerProducesNoAnomaly() {
        recharge(10_000, "H-1");
        recharge(5_000, "H-2");
        recharge(3_000, "H-3");

        assertThat(myAnomalies()).isEmpty();
        assertThat(usersMissingEarlyTransactions()).doesNotContain(userId);
    }

    /** 序号断档：第 2 笔没了。快照仍能对上（150 = 100 + 50），所以只有连号检查抓得住 */
    @Test
    void sequenceGapIsDetected() {
        insertRaw(1, 100, 100);
        insertRaw(3, 50, 150);

        List<WalletLedgerAnomalyVO> anomalies = myAnomalies();

        assertThat(anomalies).hasSize(1);
        assertThat(anomalies.get(0).getBalanceSeq()).isEqualTo(3);
        assertThat(anomalies.get(0).getPrevSeq()).as("前一笔应当取排序后的上一行，即 seq=1")
                .isEqualTo(1);
    }

    /** 记错了：序号是连的，但余额快照接不上 */
    @Test
    void balanceMismatchIsDetected() {
        insertRaw(1, 100, 100);
        insertRaw(2, 50, 999);

        List<WalletLedgerAnomalyVO> anomalies = myAnomalies();

        assertThat(anomalies).hasSize(1);
        assertThat(anomalies.get(0).getBalanceSeq()).isEqualTo(2);
        assertThat(anomalies.get(0).getPrevBalance() + anomalies.get(0).getAmount())
                .as("100 + 50 应当等于 150，而不是记下的 999")
                .isEqualTo(150)
                .isNotEqualTo(anomalies.get(0).getBalanceAfter());
    }

    /**
     * 最早几笔被删：<b>窗口函数看不见这一格</b>。
     *
     * <p>删掉开头之后，剩下的序列仍然首尾自洽（seq=2 → 3 连号、余额也接得上），
     * 所以 {@code selectLedgerAnomalies} 一条都不报——这正是「首笔必须是 1」
     * 这个不变量存在的唯一理由。这条用例同时锁住两件事：盲区真的存在、补的那个查询真的补上了。
     */
    @Test
    void missingEarlyTransactionsAreInvisibleToWindowFunctionButCaughtByTheInvariant() {
        insertRaw(2, 50, 150);
        insertRaw(3, 50, 200);

        assertThat(myAnomalies()).as("LAG 对每个用户的第一笔返回 NULL，那一格被跳过").isEmpty();
        assertThat(usersMissingEarlyTransactions()).contains(userId);
    }

    // ---------- helpers ----------

    private void recharge(long amount, String bizId) {
        walletService.increase(userId, amount, "RECHARGE", "RECHARGE_ORDER",
                bizId, "RECHARGE:" + bizId, null);
    }

    /** 绕过 service 直接落一笔流水，用来造出自洽性被破坏的账本 */
    private void insertRaw(long seq, long amount, long balanceAfter) {
        WalletTransaction tx = new WalletTransaction();
        tx.setUserId(userId);
        tx.setAmount(amount);
        tx.setBalanceAfter(balanceAfter);
        tx.setBalanceSeq(seq);
        tx.setType("RECHARGE");
        tx.setBizType("RECHARGE_ORDER");
        tx.setBizId("RAW-" + seq);
        tx.setIdempotencyKey("RAW:" + seq);
        walletTransactionMapper.insert(tx);
    }

    /** 只看自己造的那几行：全表查询会把其他用例的数据一起捞出来 */
    private List<WalletLedgerAnomalyVO> myAnomalies() {
        return walletTransactionMapper.selectLedgerAnomalies(SCAN_LIMIT).stream()
                .filter(a -> Long.valueOf(userId).equals(a.getUserId()))
                .toList();
    }

    private List<Long> usersMissingEarlyTransactions() {
        return walletTransactionMapper.selectUsersWithMissingEarlyTransactions(SCAN_LIMIT);
    }
}
