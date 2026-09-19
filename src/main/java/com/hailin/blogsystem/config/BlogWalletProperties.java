package com.hailin.blogsystem.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 钱包配置。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §4.5
 *
 * <p><b>套餐为什么用配置而不是建表</b>：当前不需要运营后台，改价直接改配置。
 * 升级触发条件——套餐需要频繁变更 / 需要分用户分渠道定价时，再升级成表 + 管理接口。
 */
@Data
@ConfigurationProperties(prefix = "blog.wallet")
public class BlogWalletProperties {

    /**
     * 注册赠送的初始额度（credit）。0 = 不送。
     *
     * <p>为什么需要它：计费开关默认开启，不送额度的话新账号注册完**立刻**就会
     * 「额度不足」——「注册就能用」和「计费上线」本来是冲突的，这一笔是解法。
     *
     * <p>存量账号不走这里（他们在计费上线前就注册了），自己去钱包页充值补上即可，
     * 所以不需要为回填写 SQL。
     */
    private long initialCredit = 0;

    /**
     * 充值套餐：key = packageCode，value = 套餐内容。
     *
     * <p>⚠️ 前端只传 packageCode，应付金额与到账额度都由后端按这里决定。
     * 让前端传金额，等于把「自助发钱」的接口开出去。
     */
    private Map<String, Package> packages = new LinkedHashMap<>();

    @Data
    public static class Package {
        /** 应付金额（分，真实货币口径；虚拟支付下仅用于展示） */
        private long payAmount;
        /** 到账额度（credit） */
        private long creditAmount;
    }
}
