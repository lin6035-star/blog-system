package com.hailin.blogsystem.entity.vo;

import com.hailin.blogsystem.entity.WalletRechargeOrder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 充值订单视图。
 *
 * <p>只暴露前端要用的字段——{@code version} 属于内部并发控制，不出现在接口里。
 */
@Data
public class RechargeOrderVO {

    private String orderNo;
    private String packageCode;
    private Long payAmount;
    private Long creditAmount;
    private String status;
    private LocalDateTime paidAt;
    private LocalDateTime createdAt;

    public static RechargeOrderVO from(WalletRechargeOrder order) {
        RechargeOrderVO vo = new RechargeOrderVO();
        vo.setOrderNo(order.getOrderNo());
        vo.setPackageCode(order.getPackageCode());
        vo.setPayAmount(order.getPayAmount());
        vo.setCreditAmount(order.getCreditAmount());
        vo.setStatus(order.getStatus());
        vo.setPaidAt(order.getPaidAt());
        vo.setCreatedAt(order.getCreatedAt());
        return vo;
    }
}
