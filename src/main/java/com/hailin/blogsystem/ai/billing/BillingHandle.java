package com.hailin.blogsystem.ai.billing;

/**
 * 一次预扣的句柄——结算 / 释放都靠它。
 *
 * <p>它只承载「这次调用预扣了谁多少钱」，不含任何业务语义：
 * 结算路径由调用方在拿到 LLM 结果后按实际用量调用 {@code settle}，
 * 失败 / 取消路径调用 {@code release}。
 *
 * @param orderNo        计费单号（流水幂等键由它派生，不能直接用它当流水键）
 * @param userId         付费用户
 * @param reservedCredit 预扣额度——结算时退款 = {@code reservedCredit - actualCredit}
 */
public record BillingHandle(String orderNo, Long userId, long reservedCredit) {
}
