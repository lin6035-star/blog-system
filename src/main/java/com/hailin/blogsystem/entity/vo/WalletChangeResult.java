package com.hailin.blogsystem.entity.vo;

/**
 * 余额变更结果。
 *
 * @param success      是否成功
 * @param balanceAfter 变更后余额（失败时无意义）
 */
public record WalletChangeResult(boolean success, long balanceAfter) {

    public static WalletChangeResult ok(long balanceAfter) {
        return new WalletChangeResult(true, balanceAfter);
    }

    /** 扣减失败：余额 ≤ 0，或钱包行不存在 */
    public static WalletChangeResult insufficient() {
        return new WalletChangeResult(false, 0L);
    }
}
