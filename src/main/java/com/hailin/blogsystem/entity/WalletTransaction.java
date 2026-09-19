package com.hailin.blogsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 钱包流水（只增不改）。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §2②
 *
 * <p>只改余额不记流水 = 出争议无法追溯。{@code balance_after} 快照是对账关键：
 * 逐笔回放校验「上一笔.balance_after + 本笔.amount == 本笔.balance_after」。
 */
@Data
@TableName("wallet_transaction")
public class WalletTransaction {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;

    /** 变动额度，有符号：+1000 充值 / -50 消费 */
    private Long amount;

    /** 变动后余额快照 */
    private Long balanceAfter;

    /** 对应 user_wallet.balance_seq */
    private Long balanceSeq;

    /** RECHARGE / AI_RESERVE / AI_REFUND / SECKILL_GRANT */
    private String type;

    /** RECHARGE_ORDER / AI_BILLING / SECKILL_ORDER */
    private String bizType;

    private String bizId;

    /**
     * 服务端派生的记账幂等键，<b>不能用客户端的 Idempotency-Key</b>
     * （那个只做请求去重）。
     *
     * <p>⚠️ 同一笔业务的多条流水不能共用键：例如一张 AI 计费单要写「预扣 + 退款」两笔，
     * 共用一个键会让第二笔撞上 {@code uk_user_idem} → 事务回滚、钱退不回来。
     * 正确做法是按操作类型派生：{@code AI_RESERVE:{orderNo}} / {@code AI_REFUND:{orderNo}}。
     */
    private String idempotencyKey;

    private String remark;
    private LocalDateTime createdAt;
}
