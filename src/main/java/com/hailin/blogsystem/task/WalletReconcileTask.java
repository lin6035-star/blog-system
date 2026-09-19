package com.hailin.blogsystem.task;

import com.hailin.blogsystem.entity.vo.WalletLedgerAnomalyVO;
import com.hailin.blogsystem.mapper.WalletTransactionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 钱包流水对账（设计稿 §7.3）。
 *
 * <p><b>判据与 {@link SeckillReconcileTask} 正好相反</b>：那边判「最终收敛」，这边判<b>必须相等</b>。
 * 秒杀的不一致有合理来源（异步落库的延迟），等一会儿就自己长回来了；而流水是只增不改的账本
 * ——余额变更与流水在同一个事务里写（{@code WalletService.applyChange}），
 * <b>断档不会自愈，它只会一直在那儿</b>。所以这里不设「连续 N 轮」的容忍，发现即告警。
 *
 * <p><b>查的是流水自身，不是「余额 = 流水累计」</b>。后者会被任何一次手改余额的 SQL 打破
 * （验证指南里就教过用 {@code UPDATE user_wallet} 造场景），那是造场景不是坏账。
 * 流水内部的自洽性则不受影响——而它才是「有没有代码绕过 {@code applyChange}」的判据。
 *
 * <p>⚠️ <b>刻意不自动修复</b>：余额和流水哪个是对的，只有人知道。补一笔流水或把余额改回去，
 * 都是拿一个猜测去覆盖事实。账本出问题时，最不该做的就是让程序自己决定真相。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class WalletReconcileTask {

    /**
     * 单轮最多报多少条。
     *
     * <p>不一致极少单独出现——同一段代码路径出错会一次污染一批用户，
     * 而它们的日志长得一模一样，全部打出来只是把真正有用的那行淹掉。
     */
    private static final int MAX_REPORT = 50;

    private final WalletTransactionMapper walletTransactionMapper;

    @Scheduled(fixedDelay = 60 * 60 * 1000L)
    public void reconcileLedger() {
        reportDiscontinuousRows();
        reportMissingEarlyTransactions();
    }

    /** 接不上前一笔的行：序号断档（漏流水）或余额快照对不上（记错了） */
    private void reportDiscontinuousRows() {
        List<WalletLedgerAnomalyVO> anomalies =
                walletTransactionMapper.selectLedgerAnomalies(MAX_REPORT);
        if (anomalies.isEmpty()) {
            return;
        }

        log.error("[WALLET-RECONCILE] 流水自洽性被破坏，发现 {} 条异常{}。"
                        + "余额变更与流水在同一事务里写，断档只可能来自「有代码绕过 "
                        + "WalletService.applyChange 直接改了余额」，或有人手工删过流水。"
                        + "不要自动修复——余额和流水哪个是对的只有人知道",
                anomalies.size(),
                anomalies.size() >= MAX_REPORT ? "（已达单轮上限，可能还有更多）" : "");

        for (WalletLedgerAnomalyVO a : anomalies) {
            // 一行里同时给出「期望」和「实际」，读日志的人不用回去翻库
            log.error("[WALLET-RECONCILE] 用户 {} 第 {} 笔接不上：上一笔 seq={} 余额={}，"
                            + "本笔 amount={} 余额快照={}（期望 seq={}、余额={}）",
                    a.getUserId(), a.getBalanceSeq(), a.getPrevSeq(), a.getPrevBalance(),
                    a.getAmount(), a.getBalanceAfter(),
                    a.getPrevSeq() + 1, a.getPrevBalance() + a.getAmount());
        }
    }

    /** 最早一笔不是第 1 号的用户：窗口函数看不到的那一格，见 Mapper 注释 */
    private void reportMissingEarlyTransactions() {
        List<Long> userIds =
                walletTransactionMapper.selectUsersWithMissingEarlyTransactions(MAX_REPORT);
        if (userIds.isEmpty()) {
            return;
        }

        log.error("[WALLET-RECONCILE] {} 个用户的最早一笔流水不是第 1 号，疑似开头的流水被删过。"
                        + "剩下的序列仍然首尾自洽，只有这条不变量能发现：{}",
                userIds.size(), userIds);
    }
}
