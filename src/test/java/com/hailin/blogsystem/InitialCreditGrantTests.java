package com.hailin.blogsystem;

import com.hailin.blogsystem.config.BlogWalletProperties;
import com.hailin.blogsystem.entity.dto.RegisterDTO;
import com.hailin.blogsystem.entity.vo.AuthVO;
import com.hailin.blogsystem.service.LoginService;
import com.hailin.blogsystem.service.WalletService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 注册赠送初始额度。
 *
 * <p>为什么值得单开一个测试类：计费开关默认开启，**没有这笔赠送，新账号注册完立刻
 * 就是「额度不足」**。它不是运营活动，而是「注册就能用」和「计费上线」之间的那一环
 * ——被摘掉时不会有任何编译错误，只会让每个新用户开箱即坏。
 *
 * <p>它容易漏的原因很实际：注册流程在 {@code LoginServiceImpl}，送额度在
 * {@code WalletService}，两者之间没有任何类型系统上的强制关联。
 *
 * <p>本类只覆盖**本地注册**入口。GitHub OAuth 那个入口依赖外部 HTTP，不在这层覆盖
 * ——这也是它那边只做「独立事务 + 失败即抛」而没有追求强原子的原因。
 */
@SpringBootTest
class InitialCreditGrantTests {

    @Autowired
    private LoginService loginService;

    @Autowired
    private WalletService walletService;

    @Autowired
    private BlogWalletProperties blogWalletProperties;

    @Test
    void registerGrantsInitialCredit() {
        long expected = initialCreditOrFail();

        AuthVO auth = loginService.register(registerDTO("initial_grant_"));
        Long userId = auth.getUsersVO().getId();

        assertThat(walletService.getWallet(userId).getBalance())
                .as("注册后应当立刻带着额度，而不是等用户自己去钱包页充值")
                .isEqualTo(expected);
    }

    /**
     * 只可能送一次。
     *
     * <p>第二次要撞 {@code uk_user_idem} 而**不是**静默成功——「悄悄多送一笔」
     * 在钱上是不能接受的降级，宁可抛出来让人看见。
     */
    @Test
    void grantInitialCreditHappensOnlyOnce() {
        long expected = initialCreditOrFail();
        Long userId = loginService.register(registerDTO("initial_once_")).getUsersVO().getId();

        assertThatThrownBy(() -> walletService.grantInitialCredit(userId))
                .isInstanceOf(DuplicateKeyException.class);

        assertThat(walletService.getWallet(userId).getBalance()).isEqualTo(expected);
        assertThat(walletService.listBill(userId, 1, 10).getTotal())
                .as("账单里也只该有一条「注册赠送」")
                .isEqualTo(1);
    }

    // ---------- helpers ----------

    private long initialCreditOrFail() {
        long initial = blogWalletProperties.getInitialCredit();
        assertThat(initial)
                .as("测试配置必须开启初始额度（blog.wallet.initial-credit），否则本类两条用例都没有意义")
                .isPositive();
        return initial;
    }

    private RegisterDTO registerDTO(String prefix) {
        String username = prefix + System.nanoTime();
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername(username);
        dto.setNickname(username);
        dto.setPassword("password123");
        dto.setConfirmPassword("password123");
        return dto;
    }
}
