package com.hailin.blogsystem.service;

/**
 * 秒杀入账（消费侧的 DB 事务）。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §6.5
 *
 * <p><b>「要不要重投」编码在返回方式里，而不是返回值里</b>：
 * 正常返回 = 已到终态、可以 ACK；抛异常 = 技术失败、不要 ACK。
 * 用 boolean 表达的话，调用方总会有一个「忘了看返回值就直接 ACK」的机会，
 * 而那个错误的表现是「钱没到账但消息没了」——静默且不可恢复。
 */
public interface SeckillSettleService {

    /**
     * 处理一条入账事件。
     *
     * <p>正常返回涵盖三种终态：已入账、售罄落 FAILED、以及「重投时查到已有终态订单
     * 直接跳过」。三者都该 ACK。
     */
    void settle(Long activityId, Long userId);

    /**
     * 死信：技术失败重试超限，落 {@code FAILED_RETRY} 终态。
     *
     * <p>没有这个状态的话，「技术失败重试中」和「消费者崩了」在 PEL 里
     * 长得一模一样，对账无法区分二者。
     */
    void markDeadLetter(Long activityId, Long userId);
}
