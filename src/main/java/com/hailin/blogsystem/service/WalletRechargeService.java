package com.hailin.blogsystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hailin.blogsystem.entity.WalletRechargeOrder;
import com.hailin.blogsystem.entity.vo.RechargeOrderVO;
import com.hailin.blogsystem.entity.vo.WalletPackageVO;

import java.util.List;

/**
 * 充值订单服务（虚拟支付，零 Redis）。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §4
 */
public interface WalletRechargeService extends IService<WalletRechargeOrder> {

    /** 充值套餐列表（来自 blog.wallet.packages 配置） */
    List<WalletPackageVO> listPackages();

    /** 下单：按套餐快照落订单行，状态 PENDING */
    RechargeOrderVO createOrder(String packageCode);

    /** 支付：五分支（不存在 / 越权 / 已取消 / 已支付幂等 / PENDING 走 CAS） */
    RechargeOrderVO pay(String orderNo);

    /**
     * 把订单置为已支付并加余额、写流水——<b>事务内核</b>。
     *
     * <p>现在由 {@link #pay(String)} 调用；将来接真实支付时由异步回调调用，
     * 接口入口会变，这个方法不变（设计稿 §4.4）。
     */
    RechargeOrderVO completePaidOrder(String orderNo);
}
