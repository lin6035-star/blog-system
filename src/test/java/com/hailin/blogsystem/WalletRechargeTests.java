package com.hailin.blogsystem;

import com.hailin.blogsystem.entity.WalletRechargeOrder;
import com.hailin.blogsystem.entity.vo.RechargeOrderVO;
import com.hailin.blogsystem.entity.vo.WalletPackageVO;
import com.hailin.blogsystem.exception.BusinessException;
import com.hailin.blogsystem.service.WalletRechargeService;
import com.hailin.blogsystem.service.WalletService;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 充值订单：五分支 + 幂等 + 越权。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §4.2
 *
 * <p>pay 的五个分支必须全部走到——初稿曾把「影响行数 0」一律当成幂等成功，
 * 那把「订单已取消」「越权」也当成了成功。出错方向是钱：多给额度或给错人。
 */
@SpringBootTest
class WalletRechargeTests {

    @Autowired
    private WalletRechargeService walletRechargeService;

    @Autowired
    private WalletService walletService;

    private final long userId = 9_200_000_000_000L + System.nanoTime() % 1_000_000_000L;
    private final long otherUserId = userId + 1;

    @BeforeEach
    void setUp() {
        UserContext.set(userId);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ---------- 下单 ----------

    @Test
    void listPackagesComesFromConfig() {
        List<WalletPackageVO> packages = walletRechargeService.listPackages();

        assertThat(packages).isNotEmpty();
        assertThat(packages).allSatisfy(pkg -> {
            assertThat(pkg.getCode()).isNotBlank();
            assertThat(pkg.getPayAmount()).isPositive();
            assertThat(pkg.getCreditAmount()).isPositive();
        });
    }

    @Test
    void createOrderSnapshotsAmountsFromPackageNotFromRequest() {
        WalletPackageVO pkg = walletRechargeService.listPackages().get(0);

        RechargeOrderVO order = walletRechargeService.createOrder(pkg.getCode());

        assertThat(order.getOrderNo()).startsWith("W");
        assertThat(order.getStatus()).isEqualTo("PENDING");
        // 金额来自套餐快照——请求体里根本没有金额字段
        assertThat(order.getPayAmount()).isEqualTo(pkg.getPayAmount());
        assertThat(order.getCreditAmount()).isEqualTo(pkg.getCreditAmount());
    }

    @Test
    void createOrderRejectsUnknownPackage() {
        assertThatThrownBy(() -> walletRechargeService.createOrder("PACK_NOT_EXIST"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("套餐不存在");
    }

    // ---------- pay 五分支 ----------

    @Test
    void payCreditsWalletAndMarksOrderPaid() {
        WalletPackageVO pkg = walletRechargeService.listPackages().get(0);
        RechargeOrderVO order = walletRechargeService.createOrder(pkg.getCode());

        RechargeOrderVO paid = walletRechargeService.pay(order.getOrderNo());

        assertThat(paid.getStatus()).isEqualTo("PAID");
        assertThat(paid.getPaidAt()).isNotNull();
        assertThat(walletService.getWallet(userId).getBalance()).isEqualTo(pkg.getCreditAmount());
    }

    /**
     * 幂等：重复支付只加一次钱。
     *
     * <p>这是整条充值链路最要紧的一条——出错方向是「用户白拿额度」。
     */
    @Test
    void payIsIdempotentOnSecondCall() {
        WalletPackageVO pkg = walletRechargeService.listPackages().get(0);
        RechargeOrderVO order = walletRechargeService.createOrder(pkg.getCode());

        walletRechargeService.pay(order.getOrderNo());
        RechargeOrderVO second = walletRechargeService.pay(order.getOrderNo());

        assertThat(second.getStatus()).isEqualTo("PAID");
        assertThat(walletService.getWallet(userId).getBalance()).isEqualTo(pkg.getCreditAmount());
    }

    @Test
    void payRejectsOtherUsersOrder() {
        WalletPackageVO pkg = walletRechargeService.listPackages().get(0);
        RechargeOrderVO order = walletRechargeService.createOrder(pkg.getCode());

        UserContext.set(otherUserId);
        assertThatThrownBy(() -> walletRechargeService.pay(order.getOrderNo()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权");

        // 越权请求不能造成任何余额变动
        assertThat(walletService.getWallet(userId).getBalance()).isZero();
    }

    @Test
    void payRejectsMissingOrder() {
        assertThatThrownBy(() -> walletRechargeService.pay("W_NOT_EXIST"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("订单不存在");
    }

    @Test
    void payRejectsCancelledOrder() {
        WalletPackageVO pkg = walletRechargeService.listPackages().get(0);
        RechargeOrderVO order = walletRechargeService.createOrder(pkg.getCode());
        walletRechargeService.lambdaUpdate()
                .eq(WalletRechargeOrder::getOrderNo, order.getOrderNo())
                .set(WalletRechargeOrder::getStatus, "CANCELLED")
                .update();

        assertThatThrownBy(() -> walletRechargeService.pay(order.getOrderNo()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已取消");

        assertThat(walletService.getWallet(userId).getBalance()).isZero();
    }
}
