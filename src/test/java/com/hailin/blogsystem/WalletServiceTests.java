package com.hailin.blogsystem;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hailin.blogsystem.entity.UserWallet;
import com.hailin.blogsystem.entity.WalletTransaction;
import com.hailin.blogsystem.entity.vo.WalletChangeResult;
import com.hailin.blogsystem.entity.vo.WalletVO;
import com.hailin.blogsystem.mapper.UserWalletMapper;
import com.hailin.blogsystem.mapper.WalletTransactionMapper;
import com.hailin.blogsystem.service.WalletService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 钱包核心：余额变更 + 流水记账。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §3
 *
 * <p>这里锁住的是设计稿里最容易被"顺手改坏"的几条：
 * <ul>
 *   <li>扣减门槛是 {@code balance > 0} 而非 {@code balance >= amount}——<b>允许透支一轮</b></li>
 *   <li>{@code balance_after} 与 {@code balance_seq} 必须属于<b>这一笔</b>变更（写后读）</li>
 *   <li>钱包行懒创建：查询不写库、入账才创建</li>
 * </ul>
 *
 * <p>测试不依赖 users 表——钱包表没有指向它的外键，用独立 userId 即可，
 * 也因此不会和其他测试类互相干扰。
 */
@SpringBootTest
class WalletServiceTests {

    @Autowired
    private WalletService walletService;

    @Autowired
    private UserWalletMapper userWalletMapper;

    @Autowired
    private WalletTransactionMapper walletTransactionMapper;

    /** 每个测试方法一个独立用户：H2 内存库在整个 JVM 内共享，靠唯一 id 隔离 */
    private final long userId = 9_100_000_000_000L + System.nanoTime() % 1_000_000_000L;

    // ---------- 入账 ----------

    @Test
    void increaseCreatesWalletLazilyAndRecordsTransaction() {
        // 钱包行不存在也应当直接入账成功——这是唯一的创建机制，注册流程不建钱包
        WalletChangeResult result = recharge(1000, "ORDER-1");

        assertThat(result.success()).isTrue();
        assertThat(result.balanceAfter()).isEqualTo(1000);

        WalletVO wallet = walletService.getWallet(userId);
        assertThat(wallet.getBalance()).isEqualTo(1000);
        // 没有进行中的计费单
        assertThat(wallet.getPendingReserveCount()).isZero();

        List<WalletTransaction> txs = transactions();
        assertThat(txs).hasSize(1);
        assertThat(txs.get(0).getAmount()).isEqualTo(1000);
        assertThat(txs.get(0).getBalanceAfter()).isEqualTo(1000);
        assertThat(txs.get(0).getBalanceSeq()).isEqualTo(1L);
    }

    @Test
    void getWalletReturnsZeroInsteadOfCreatingRow() {
        WalletVO wallet = walletService.getWallet(userId);

        assertThat(wallet.getBalance()).isZero();
        // 读路径不写库
        assertThat(userWalletMapper.selectCount(
                new LambdaQueryWrapper<UserWallet>().eq(UserWallet::getUserId, userId))).isZero();
    }

    @Test
    void increaseRejectsNonPositiveAmount() {
        assertThatThrownBy(() -> walletService.increase(userId, 0, "RECHARGE", "RECHARGE_ORDER",
                "ORDER-0", "RECHARGE:ORDER-0", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- 扣减 ----------

    @Test
    void decreaseDeductsAndRecordsNegativeAmount() {
        recharge(1000, "ORDER-2");

        WalletChangeResult result = reserve(300, "B-1");

        assertThat(result.success()).isTrue();
        assertThat(result.balanceAfter()).isEqualTo(700);

        List<WalletTransaction> txs = transactions();
        // 倒序：最新的一笔是扣减
        assertThat(txs.get(0).getAmount()).isEqualTo(-300);
        assertThat(txs.get(0).getBalanceAfter()).isEqualTo(700);
        assertThat(txs.get(0).getBalanceSeq()).isEqualTo(2L);
        assertThat(txs.get(0).getType()).isEqualTo("AI_RESERVE");
    }

    /**
     * 本测试锁住的就是「允许透支一轮」这条业务规则本身。
     *
     * <p>预扣值是硬上限不是报价，用刚性门槛（balance &gt;= amount）会拒绝本可支付的请求。
     * 放行后余额扣成负数，下一次扣减才被拦住——两次断言缺一不可：
     * 只断言第一次会漏掉「门槛被误改成恒定放行」，只断言第二次会漏掉「透支被误禁」。
     */
    @Test
    void decreaseAllowsBalanceToGoNegativeThenBlocksNextCall() {
        recharge(500, "ORDER-3");

        // 预扣 3000 > 余额 500：应当放行，扣成 -2500
        WalletChangeResult first = reserve(3000, "B-2");
        assertThat(first.success()).isTrue();
        assertThat(first.balanceAfter()).isEqualTo(-2500);

        // 第二次：balance > 0 不成立，自动拦住
        WalletChangeResult second = reserve(100, "B-3");
        assertThat(second.success()).isFalse();
        assertThat(walletService.getWallet(userId).getBalance()).isEqualTo(-2500);
    }

    @Test
    void decreaseFailsWhenWalletMissing() {
        // 钱包行不存在 == 余额不足，两者语义相同
        WalletChangeResult result = reserve(100, "B-4");

        assertThat(result.success()).isFalse();
        assertThat(userWalletMapper.selectCount(
                new LambdaQueryWrapper<UserWallet>().eq(UserWallet::getUserId, userId))).isZero();
    }

    @Test
    void decreaseFailsWhenBalanceIsExactlyZero() {
        recharge(1000, "ORDER-4");
        reserve(1000, "B-5");   // 扣到 0

        // 0 不满足 balance > 0
        assertThat(reserve(1, "B-6").success()).isFalse();
    }

    // ---------- balance_seq ----------

    @Test
    void balanceSeqIncreasesMonotonicallyPerChange() {
        recharge(100, "ORDER-5");
        reserve(50, "B-7");
        recharge(200, "ORDER-6");

        assertLedgerConsistent(transactions());
    }

    // ---------- helpers ----------

    private WalletChangeResult recharge(long amount, String bizId) {
        return walletService.increase(userId, amount, "RECHARGE", "RECHARGE_ORDER",
                bizId, "RECHARGE:" + bizId, null);
    }

    private WalletChangeResult reserve(long amount, String bizId) {
        return walletService.decrease(userId, amount, "AI_RESERVE", "AI_BILLING",
                bizId, "AI_RESERVE:" + bizId, null);
    }

    /** 按 balance_seq 升序返回本用户的流水——回放顺序就是它，不是 created_at */
    private List<WalletTransaction> transactions() {
        return walletTransactionMapper.selectList(
                new LambdaQueryWrapper<WalletTransaction>()
                        .eq(WalletTransaction::getUserId, userId)
                        .orderByDesc(WalletTransaction::getBalanceSeq));
    }

    /**
     * 断言账本自洽：{@code balance_seq} 是 1..n 的连续序列，且每笔 {@code balance_after} = 上一笔 + 本笔。
     *
     * <p>前者断「没漏流水」（序号断档说明有一笔没写进来）；
     * 后者断「写后读拿到的快照属于本笔」——顺序写反了它会错位。
     */
    private void assertLedgerConsistent(List<WalletTransaction> descending) {
        long expected = descending.size();
        for (WalletTransaction tx : descending) {
            assertThat(tx.getBalanceSeq()).isEqualTo(expected--);
        }
        long running = 0;
        for (int i = descending.size() - 1; i >= 0; i--) {
            WalletTransaction tx = descending.get(i);
            running += tx.getAmount();
            assertThat(tx.getBalanceAfter()).isEqualTo(running);
        }
    }
}
