package com.hailin.blogsystem.service;

import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.ai.billing.BillingHandle;
import com.hailin.blogsystem.exception.InsufficientBalanceException;

/**
 * AI 计费：预扣 / 结算 / 释放。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §5
 *
 * <p><b>事务边界是这个类最要紧的地方</b>：预扣和结算是两个独立的短事务，
 * 模型调用必须夹在它们中间、<b>不能包进事务里</b>——否则一次 LLM 调用的
 * 几十秒到几分钟会一直持有钱包行锁和数据库连接，并发一上来连接池就满了（设计稿 §5.5）。
 *
 * <p>调用方的正确写法：
 * <pre>
 * BillingHandle handle = billingService.reserve(...);   // 短事务 A
 * try {
 *     ...调用 LLM...                                     // 事务外
 *     billingService.settle(handle, usage);              // 短事务 B
 * } catch (Exception e) {
 *     billingService.release(handle);                    // 短事务 B'
 * }
 * </pre>
 */
public interface AiBillingService {

    /**
     * 预扣：建计费单 + 从钱包扣款 + 写流水，同一个短事务。
     *
     * @param reservedCredit 预扣额度，由各路径按自己的硬上限算好
     * @return 句柄；<b>计费关闭 / 无 userId / 额度为 0 时返回 null</b>，
     *         调用方拿到 null 直接跳过后续结算与释放
     * @throws InsufficientBalanceException 余额 ≤ 0（此时整个预扣事务回滚，不会留下单据）
     */
    BillingHandle reserve(String bizType, String bizId, Long userId, long reservedCredit);

    /**
     * 结算：按实际用量扣，退回差额。
     *
     * <p>usage 缺失（{@code totalTokens <= 0}）时<b>按预扣全额结算</b> + 告警，
     * 不引入"挂起等人工补录"的第四种状态（设计稿 §5.3）。
     */
    void settle(BillingHandle handle, TokenUsageAccumulator usage);

    /** 释放：调用失败 / 用户取消，全额退回 */
    void release(BillingHandle handle);

    /** 回填关联资源（assistant 消息 id / agent run id），仅用于观测 */
    void bindResource(BillingHandle handle, String resourceId);
}
