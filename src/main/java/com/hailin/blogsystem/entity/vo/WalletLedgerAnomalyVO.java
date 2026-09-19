package com.hailin.blogsystem.entity.vo;

import lombok.Data;

/**
 * 一条不自洽的流水，以及它在序列里的前一笔。
 *
 * <p>由 {@code WalletTransactionMapper.selectLedgerAnomalies} 用窗口函数（{@code LAG}）一次查出：
 * 只有把前一笔的 {@code balance_after} / {@code balance_seq} 拉到同一行上，
 * 才能用 SQL 判断「这一笔接不接得上」，不需要把全表捞进内存逐笔比对。
 *
 * <p>两种异常共用这一个形状，靠字段自己区分：
 * <ul>
 *   <li>{@code prevSeq + 1 != balanceSeq} —— <b>漏流水</b>（序号断档）</li>
 *   <li>{@code prevBalance + amount != balanceAfter} —— <b>记错了</b>（余额快照对不上）</li>
 * </ul>
 * 两者经常同时出现（一笔流水没写，后面全错位），所以不拆成两个查询。
 */
@Data
public class WalletLedgerAnomalyVO {

    private Long userId;

    /** 本笔的序号 */
    private Long balanceSeq;

    /** 本笔的变动额，有符号 */
    private Long amount;

    /** 本笔之后的余额快照 */
    private Long balanceAfter;

    /** 上一笔之后的余额快照 */
    private Long prevBalance;

    /** 上一笔的序号 */
    private Long prevSeq;
}
