package com.hailin.blogsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户钱包账户（一用户一行）。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §2①
 *
 * <p><b>balance 允许为负</b>：预扣门槛是 {@code balance > 0} 而不是 {@code balance >= reserved}，
 * 所以「扣成负数」是正常业务状态（透支一轮），不是异常。
 * 负余额的幅度恒小于造成它的那笔预扣——推导见设计稿 §3。
 */
@Data
@TableName("user_wallet")
public class UserWallet {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;

    /** 余额，单位 credit；允许为负 */
    private Long balance;

    /**
     * 余额变更序号：每次余额变更原子 +1，只用于流水严格排序。
     *
     * <p><b>不是乐观锁 version</b>——余额扣减仍走条件更新，不做版本号 CAS。
     * 它的价值在流水回放：时间戳会相同，Snowflake id 的大小也不等于钱包行锁的获得顺序，
     * 只有这个原子递增的序号能还原真实的串行修改顺序。
     */
    private Long balanceSeq;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
