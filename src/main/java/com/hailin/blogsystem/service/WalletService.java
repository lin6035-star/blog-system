package com.hailin.blogsystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hailin.blogsystem.entity.UserWallet;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.entity.vo.WalletBillEntryVO;
import com.hailin.blogsystem.entity.vo.WalletChangeResult;
import com.hailin.blogsystem.entity.vo.WalletVO;

/**
 * 钱包服务。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §3 / §4
 *
 * <p><b>所有余额变更都必须走这里</b>——「改余额」和「写流水」必须在同一个事务、
 * 同一把行锁未释放时完成，分散到各处写早晚会漏掉一边。
 */
public interface WalletService extends IService<UserWallet> {

    /**
     * 查询钱包。
     *
     * <p>钱包行不存在时按余额 0 返回，<b>不主动创建</b>（读路径不写库）。
     */
    WalletVO getWallet(Long userId);

    /**
     * 用户账单（**聚合视角**）。
     *
     * <p>按业务聚合：一次 AI 调用 = 一条净额，不暴露预扣 / 退回的中间态。
     * 原始流水仍完整保留在 {@code wallet_transaction} 里（逐笔回放对账要用），
     * 只是不面向用户展示——见 {@link com.hailin.blogsystem.entity.vo.WalletBillEntryVO}。
     */
    PageVO<WalletBillEntryVO> listBill(Long userId, long page, long pageSize);

    /**
     * 确保钱包行存在（幂等）。
     *
     * <p>这是<b>唯一的创建机制</b>：存量用户和任何入账路径都靠它兜底，
     * 所以不需要在注册流程里额外创建钱包。
     */
    void ensureWallet(Long userId);

    /**
     * 注册赠送初始额度（按 {@code blog.wallet.initial-credit}，配置为 0 时什么都不做）。
     *
     * <p>幂等键 {@code INITIAL:{userId}} + {@code uk_user_idem} 保证一个用户只送一次
     * ——重复调用会撞唯一索引抛异常，而不是悄悄多送一笔。
     *
     * <p><b>必须与「创建用户」在同一事务内</b>：送礼失败却把用户建出来了，
     * 用户会卡在「注册报错但用户名已被占用」上，重试都重试不了。
     */
    void grantInitialCredit(Long userId);

    /**
     * 入账（充值 / 秒杀发放）。
     *
     * @param type           流水类型：RECHARGE / SECKILL_GRANT
     * @param bizType        业务类型：RECHARGE_ORDER / SECKILL_ORDER
     * @param bizId          业务主键
     * @param idempotencyKey 服务端派生的记账幂等键，<b>不能用客户端 Idempotency-Key</b>
     */
    WalletChangeResult increase(Long userId, long amount, String type, String bizType,
                                String bizId, String idempotencyKey, String remark);

    /**
     * 扣减（AI 预扣）。
     *
     * <p>门槛是 {@code balance > 0} 而非 {@code balance >= amount}——允许把余额扣成负数，
     * 理由见设计稿 §3。返回 {@link WalletChangeResult#insufficient()} 表示余额 ≤ 0，
     * 调用方应拒绝这次调用。
     *
     * @param type 流水类型：AI_RESERVE
     */
    WalletChangeResult decrease(Long userId, long amount, String type, String bizType,
                                String bizId, String idempotencyKey, String remark);
}
