package com.hailin.blogsystem.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户账单条目（**聚合视角**）。
 *
 * <p><b>为什么不能直接给用户看原始流水</b>：{@code wallet_transaction} 是<b>记账视角</b>——
 * 一次 AI 调用会写两条（预扣 {@code AI_RESERVE} + 结算退回 {@code AI_REFUND}），
 * 因为「改余额」和「记账」必须逐笔对应，否则按 {@code balance_seq} 的逐笔回放会断档。
 *
 * <p>但用户想看的只有两件事：<b>充了多少、花了多少</b>。把预扣和退回原样摆出来，
 * 用户看到的是「扣了 400 又退了 365」，而不是「花了 35」——中间态是记账的实现细节。
 *
 * <p>所以这个视图按 {@code biz_id} 聚合：同一笔业务的预扣与退款相加就是净额。
 * <b>预扣中（还没结算）的不出现</b>——净额没定下来之前，它不是一笔已发生的事实。
 */
@Data
public class WalletBillEntryVO {

    /** 业务主键（同一笔业务的多条流水共享它） */
    private String bizId;

    /**
     * 业务类型，前端据此映射文案：
     * {@code RECHARGE_ORDER} = 充值到账 / {@code SECKILL_ORDER} = 秒杀到账 / {@code AI_BILLING} = AI 消费
     */
    private String bizType;

    /** 净额，有符号：正数入账、负数消费 */
    private Long amount;

    /** 该笔业务结算后的余额快照 */
    private Long balanceAfter;

    /** 业务完成时间（AI 是结算时间，充值 / 发放是入账时间） */
    private LocalDateTime createdAt;
}
