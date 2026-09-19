package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hailin.blogsystem.config.BlogWalletProperties;
import com.hailin.blogsystem.entity.AiBillingOrder;
import com.hailin.blogsystem.entity.UserWallet;
import com.hailin.blogsystem.entity.WalletTransaction;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.entity.vo.WalletBillEntryVO;
import com.hailin.blogsystem.entity.vo.WalletChangeResult;
import com.hailin.blogsystem.entity.vo.WalletVO;
import com.hailin.blogsystem.mapper.AiBillingOrderMapper;
import com.hailin.blogsystem.mapper.UserWalletMapper;
import com.hailin.blogsystem.mapper.WalletTransactionMapper;
import com.hailin.blogsystem.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 钱包服务实现。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §3 / §4
 *
 * <p><b>为什么钱包是纯 DB、一点 Redis 都不用</b>：判据是「数据可不可以丢」。
 * 浏览量少几个无所谓，余额少一分就是账不平。引入 Redis 只会凭空造出
 * 「Redis 扣了但 DB 没落」的窗口，而这个窗口在钱上没有可接受的代价。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WalletServiceImpl extends ServiceImpl<UserWalletMapper, UserWallet> implements WalletService {

    /** ai_billing_order.status：预扣已扣、尚未结算 */
    private static final String BILLING_STATUS_RESERVED = "RESERVED";

    /** 注册赠送的流水类型 / 业务类型（账单按 bizType 映射文案） */
    private static final String INITIAL_GRANT = "INITIAL_GRANT";

    private final UserWalletMapper userWalletMapper;
    private final WalletTransactionMapper walletTransactionMapper;
    private final AiBillingOrderMapper aiBillingOrderMapper;
    private final BlogWalletProperties blogWalletProperties;

    @Override
    public WalletVO getWallet(Long userId) {
        UserWallet wallet = selectByUserId(userId);

        WalletVO vo = new WalletVO();
        // 钱包行不存在 == 余额 0（懒创建），读路径不写库
        vo.setBalance(wallet == null ? 0L : wallet.getBalance());
        vo.setPendingReserveCount(aiBillingOrderMapper.selectCount(
                new LambdaQueryWrapper<AiBillingOrder>()
                        .eq(AiBillingOrder::getUserId, userId)
                        .eq(AiBillingOrder::getStatus, BILLING_STATUS_RESERVED)));
        return vo;
    }

    @Override
    public PageVO<WalletBillEntryVO> listBill(Long userId, long page, long pageSize) {
        long offset = Math.max(0L, (page - 1) * pageSize);
        List<WalletBillEntryVO> list = walletTransactionMapper.selectBillPage(userId, pageSize, offset);
        long total = walletTransactionMapper.countBill(userId);
        return new PageVO<>(list, total, page, pageSize);
    }

    @Override
    public void ensureWallet(Long userId) {
        try {
            userWalletMapper.insertWallet(IdWorker.getId(), userId);
        } catch (DuplicateKeyException e) {
            // 并发下另一个事务刚创建了同一个用户的钱包行——幂等语义的正常路径，不是错误。
            // 不重试、不上抛：uk_user_id 就是这里的防线。
        }
    }

    @Override
    @Transactional
    public void grantInitialCredit(Long userId) {
        long initial = blogWalletProperties.getInitialCredit();
        if (initial <= 0) {
            return;
        }
        // 调本类的 increase() 属于自调用，不走代理——但事务由本方法提供，语义等价。
        // bizId 用 userId：一个用户只可能有一笔注册赠送，幂等键 INITIAL:{userId} 锁死这一点。
        increase(userId, initial, INITIAL_GRANT, INITIAL_GRANT,
                String.valueOf(userId), "INITIAL:" + userId, "注册赠送");
    }

    @Override
    @Transactional
    public WalletChangeResult increase(Long userId, long amount, String type, String bizType,
                                       String bizId, String idempotencyKey, String remark) {
        if (amount <= 0) {
            throw new IllegalArgumentException("入账金额必须为正数：" + amount);
        }
        ensureWallet(userId);
        if (userWalletMapper.add(userId, amount) != 1) {
            // ensureWallet 之后钱包行必然存在，走到这里说明有并发删行或其他数据异常
            throw new IllegalStateException("钱包入账影响行数异常：user_id=" + userId);
        }
        return recordTransaction(userId, amount, type, bizType, bizId, idempotencyKey, remark);
    }

    @Override
    @Transactional
    public WalletChangeResult decrease(Long userId, long amount, String type, String bizType,
                                       String bizId, String idempotencyKey, String remark) {
        if (amount <= 0) {
            throw new IllegalArgumentException("扣减金额必须为正数：" + amount);
        }
        if (userWalletMapper.deduct(userId, amount) == 0) {
            // 余额 ≤ 0 或钱包行不存在——两者语义相同：不能发起这次调用。
            // 余额不足是正常业务分支，不是错误，所以直接返回而不是抛异常。
            return WalletChangeResult.insufficient();
        }
        return recordTransaction(userId, -amount, type, bizType, bizId, idempotencyKey, remark);
    }

    /**
     * 写后读余额快照 + 落流水。
     *
     * <p>⚠️ <b>必须在余额 UPDATE 之后、同一事务内调用</b>，顺序不能反：
     * UPDATE 拿到的行锁在事务提交前一直持有，同一事务内读到的 {@code balance / balance_seq}
     * 必然是这次更新之后的值，不会被别人插队。若先读后写，{@code balance_after} 快照
     * 会和实际不符，逐笔回放校验（设计稿 §7.3）就会报假警。
     *
     * <p>MyBatis 一级缓存不构成干扰：update 语句默认 {@code flushCache=true}，
     * 执行时已经清空本地缓存，所以这里的查询读到的是更新后的值。
     */
    private WalletChangeResult recordTransaction(Long userId, long delta, String type, String bizType,
                                                 String bizId, String idempotencyKey, String remark) {
        UserWallet wallet = selectByUserId(userId);
        if (wallet == null) {
            throw new IllegalStateException("钱包行不存在，无法记录流水：user_id=" + userId);
        }

        WalletTransaction tx = new WalletTransaction();
        tx.setUserId(userId);
        tx.setAmount(delta);
        tx.setBalanceAfter(wallet.getBalance());
        tx.setBalanceSeq(wallet.getBalanceSeq());
        tx.setType(type);
        tx.setBizType(bizType);
        tx.setBizId(bizId);
        tx.setIdempotencyKey(idempotencyKey);
        tx.setRemark(remark);
        // 撞唯一索引就直接抛：上层幂等（订单状态 CAS / uk_biz）已经挡住重复，
        // 走到这里说明真的重了——吞掉等于「钱动了但账没记」。
        walletTransactionMapper.insert(tx);

        log.info("[WALLET] user_id={} delta={} balance_after={} seq={} type={} biz={}:{}",
                userId, delta, wallet.getBalance(), wallet.getBalanceSeq(), type, bizType, bizId);

        return WalletChangeResult.ok(wallet.getBalance());
    }

    private UserWallet selectByUserId(Long userId) {
        return userWalletMapper.selectOne(
                new LambdaQueryWrapper<UserWallet>().eq(UserWallet::getUserId, userId));
    }
}
