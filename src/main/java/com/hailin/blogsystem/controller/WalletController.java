package com.hailin.blogsystem.controller;

import com.hailin.blogsystem.entity.dto.WalletRechargeDTO;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.entity.vo.RechargeOrderVO;
import com.hailin.blogsystem.entity.vo.WalletBillEntryVO;
import com.hailin.blogsystem.entity.vo.WalletPackageVO;
import com.hailin.blogsystem.entity.vo.WalletVO;
import com.hailin.blogsystem.service.WalletRechargeService;
import com.hailin.blogsystem.service.WalletService;
import com.hailin.blogsystem.utils.Result;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 钱包接口。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §4
 *
 * <p>全部需要登录（{@code /api/wallet/**} 挂在 JwtInterceptor 上）——
 * 游客没有钱包，也没有计费单元。
 */
@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;
    private final WalletRechargeService walletRechargeService;

    /** 当前余额 + 进行中的预扣笔数（负余额的两种含义靠这两个字段区分，见 {@link WalletVO}） */
    @GetMapping
    public Result<WalletVO> getWallet() {
        return Result.success(walletService.getWallet(currentUserId()));
    }

    /**
     * 账单（**聚合视角**）。
     *
     * <p>一次 AI 调用只出现一条净额，不带预扣 / 退回的中间态——那些是记账的实现细节，
     * 用户要看的是「充了多少、花了多少」。
     */
    @GetMapping("/bill")
    public Result<PageVO<WalletBillEntryVO>> listBill(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long pageSize) {
        return Result.success(walletService.listBill(currentUserId(), page, pageSize));
    }

    /** 充值套餐（来自 blog.wallet.packages 配置） */
    @GetMapping("/packages")
    public Result<List<WalletPackageVO>> listPackages() {
        return Result.success(walletRechargeService.listPackages());
    }

    /** 下单：只传 packageCode，金额与到账额度由后端按套餐决定 */
    @PostMapping("/recharge/orders")
    public Result<RechargeOrderVO> createOrder(@RequestBody WalletRechargeDTO dto) {
        return Result.success(walletRechargeService.createOrder(dto.getPackageCode()));
    }

    /**
     * 支付（虚拟支付：直接把订单置为已支付）。
     *
     * <p>幂等由「订单号 + 状态 CAS」提供，服务端<b>不消费</b> Idempotency-Key——
     * 前端仍可带它做按钮级防重，但记账侧的幂等不能依赖外部 key 存储
     * （项目现有的幂等组件基于 Redis，而钱的操作不能依赖 Redis，见设计稿 §4.3）。
     */
    @PostMapping("/recharge/orders/{orderNo}/pay")
    public Result<RechargeOrderVO> pay(@PathVariable String orderNo) {
        return Result.success(walletRechargeService.pay(orderNo));
    }

    /**
     * 取当前用户。
     *
     * <p>这里显式抛而不是让 null 传下去：{@code /api/wallet/**} 由 JwtInterceptor 拦截，
     * 正常情况下不可能为 null。一旦为 null 说明拦截器配置被改漏了——
     * 那时应当立刻失败，而不是静默返回「余额 0」这种看着正常、实际错误的响应。
     */
    private Long currentUserId() {
        Long userId = UserContext.get();
        if (userId == null) {
            throw new IllegalStateException("未登录上下文：/api/wallet/** 应由 JwtInterceptor 拦截");
        }
        return userId;
    }
}
