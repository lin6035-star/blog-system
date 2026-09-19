package com.hailin.blogsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 充值订单（虚拟支付）。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §2③ / §4
 *
 * <p><b>金额和到账额度都落在订单行上</b>：下单时按套餐快照写入，
 * 这样即使以后套餐改价，历史订单仍然可对账。
 */
@Data
@TableName("wallet_recharge_order")
public class WalletRechargeOrder {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String orderNo;
    private Long userId;

    /** 套餐编码（不是金额）——前端只传这个，金额由后端按配置决定 */
    private String packageCode;

    /** 应付金额（分，真实货币口径；虚拟支付下仅用于展示） */
    private Long payAmount;

    /** 到账额度（credit） */
    private Long creditAmount;

    /** PENDING / PAID / CANCELLED */
    private String status;

    private Integer version;
    private LocalDateTime paidAt;
    private LocalDateTime createdAt;
}
