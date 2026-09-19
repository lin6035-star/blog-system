package com.hailin.blogsystem.entity.dto;

import lombok.Data;

/**
 * 创建充值订单的请求体。
 *
 * <p>⚠️ <b>只有 packageCode，没有金额</b>。让前端传金额等于把「自助发钱」的接口开出去——
 * 应付金额与到账额度一律由后端按 blog.wallet.packages 决定。
 */
@Data
public class WalletRechargeDTO {

    private String packageCode;
}
