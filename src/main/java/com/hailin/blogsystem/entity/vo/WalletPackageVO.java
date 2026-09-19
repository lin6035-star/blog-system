package com.hailin.blogsystem.entity.vo;

import lombok.Data;

/**
 * 充值套餐视图（来自 blog.wallet.packages 配置）。
 *
 * <p>{@code payAmount} 是真是假由支付渠道决定——虚拟支付下它只用于展示，
 * 用户点「确认支付」不会真的扣钱。接到真实支付时这个字段才承担实际语义。
 */
@Data
public class WalletPackageVO {

    private String code;

    /** 应付金额（分） */
    private Long payAmount;

    /** 到账额度（credit） */
    private Long creditAmount;
}
