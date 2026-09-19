package com.hailin.blogsystem.service.impl;

import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.ai.billing.BillingHandle;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.AiBillingOrder;
import com.hailin.blogsystem.entity.vo.WalletChangeResult;
import com.hailin.blogsystem.entity.vo.WalletVO;
import com.hailin.blogsystem.exception.InsufficientBalanceException;
import com.hailin.blogsystem.mapper.AiBillingOrderMapper;
import com.hailin.blogsystem.service.AiBillingService;
import com.hailin.blogsystem.service.WalletService;
import com.hailin.blogsystem.utils.OrderNoGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * AI 计费实现。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §5
 *
 * <p>三条不变量，改动前先确认它们仍然成立：
 * <ol>
 *   <li><b>预扣与结算是两个独立短事务</b>，中间不持有行锁与连接</li>
 *   <li><b>结算只会退款</b>（{@code actual <= reserved}）；差额为负说明硬上限没兜住，是 bug 不是业务</li>
 *   <li><b>一张单只会产生一次退款</b>——靠 {@code status = 'RESERVED'} 条件更新保证，
 *       结算与释放共用同一个流水幂等键 {@code AI_REFUND:{orderNo}}</li>
 * </ol>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AiBillingServiceImpl implements AiBillingService {

    private static final String STATUS_RESERVED = "RESERVED";
    private static final String TYPE_AI_RESERVE = "AI_RESERVE";
    private static final String TYPE_AI_REFUND = "AI_REFUND";
    private static final String BIZ_AI_BILLING = "AI_BILLING";

    private final AiBillingOrderMapper aiBillingOrderMapper;
    private final WalletService walletService;
    private final BlogAiProperties blogAiProperties;

    @Override
    @Transactional
    public BillingHandle reserve(String bizType, String bizId, Long userId, long reservedCredit) {
        if (!enabled() || userId == null || reservedCredit <= 0) {
            return null;
        }

        String orderNo = OrderNoGenerator.next("B");
        AiBillingOrder order = new AiBillingOrder();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setBizType(bizType);
        order.setBizId(bizId);
        order.setStatus(STATUS_RESERVED);
        order.setReservedCredit(reservedCredit);
        order.setVersion(0);
        order.setExpireAt(LocalDateTime.now().plusMinutes(expireMinutes()));
        // 撞 uk_biz 会直接抛：那是真 bug（同一次调用开了两张单），不能吞
        aiBillingOrderMapper.insert(order);

        WalletChangeResult result = walletService.decrease(userId, reservedCredit,
                TYPE_AI_RESERVE, BIZ_AI_BILLING, orderNo, TYPE_AI_RESERVE + ":" + orderNo,
                bizType + " 预扣");
        if (!result.success()) {
            // 余额 ≤ 0。抛出去让整个预扣事务回滚——订单行不能留下，
            // 否则会有一条永远没人结算的 RESERVED 单
            WalletVO wallet = walletService.getWallet(userId);
            throw new InsufficientBalanceException(wallet.getBalance());
        }

        log.info("[BILLING] 预扣 user_id={} biz={}:{} reserved={} order_no={}",
                userId, bizType, bizId, reservedCredit, orderNo);
        return new BillingHandle(orderNo, userId, reservedCredit);
    }

    @Override
    @Transactional
    public void settle(BillingHandle handle, TokenUsageAccumulator usage) {
        if (handle == null) {
            return;
        }

        long actual = computeActualCredit(handle, usage);
        int affected = aiBillingOrderMapper.markSettled(
                handle.orderNo(),
                actual,
                usage == null ? 0 : usage.getTotalTokens(),
                usage == null ? 0 : usage.getPromptTokens(),
                usage == null ? 0 : usage.getCompletionTokens(),
                null);

        if (affected == 0) {
            // 已被释放（超时兜底抢先）或已结算过。退款只能发生一次，这里必须放手
            log.warn("[BILLING] 结算时单据已不是 RESERVED，跳过退款：order_no={}", handle.orderNo());
            return;
        }

        long refund = handle.reservedCredit() - actual;
        if (refund > 0) {
            walletService.increase(handle.userId(), refund, TYPE_AI_REFUND, BIZ_AI_BILLING,
                    handle.orderNo(), TYPE_AI_REFUND + ":" + handle.orderNo(), "按实际用量结算退回");
        }

        log.info("[BILLING] 结算 order_no={} reserved={} actual={} refund={}",
                handle.orderNo(), handle.reservedCredit(), actual, refund);
    }

    @Override
    @Transactional
    public void release(BillingHandle handle) {
        if (handle == null) {
            return;
        }
        if (aiBillingOrderMapper.markReleased(handle.orderNo()) == 0) {
            log.warn("[BILLING] 释放时单据已不是 RESERVED，跳过退款：order_no={}", handle.orderNo());
            return;
        }

        walletService.increase(handle.userId(), handle.reservedCredit(), TYPE_AI_REFUND, BIZ_AI_BILLING,
                handle.orderNo(), TYPE_AI_REFUND + ":" + handle.orderNo(), "调用未完成，全额退回");

        log.info("[BILLING] 释放 order_no={} refund={}", handle.orderNo(), handle.reservedCredit());
    }

    @Override
    public void bindResource(BillingHandle handle, String resourceId) {
        if (handle == null || resourceId == null) {
            return;
        }
        try {
            aiBillingOrderMapper.bindResource(handle.orderNo(), resourceId);
        } catch (Exception e) {
            // 纯观测字段，写不进去不该影响主流程
            log.warn("[BILLING] 回填 resource_id 失败：order_no={}", handle.orderNo(), e);
        }
    }

    /**
     * 实际结算额度。
     *
     * <p>两个"不能"：<b>不能</b>因为 usage 缺失就按 0 收（那等于把"统计缺失"和"零消耗"
     * 混为一谈，方向比多扣更糟）；<b>不能</b>在 {@code actual > reserved} 时按 actual 扣
     * （那等于绕开预扣把余额扣穿）。两种异常都留下可检索的告警日志。
     */
    private long computeActualCredit(BillingHandle handle, TokenUsageAccumulator usage) {
        int totalTokens = usage == null ? 0 : usage.getTotalTokens();

        if (totalTokens <= 0) {
            // 用户确实拿到了回答，按预扣全额收
            log.warn("[BILLING-USAGE-MISSING] provider 未返回 usage，按预扣全额结算：order_no={} reserved={}",
                    handle.orderNo(), handle.reservedCredit());
            return handle.reservedCredit();
        }

        long actual = ceilDiv((long) totalTokens * creditPer1kTokens(), 1000L);
        if (actual > handle.reservedCredit()) {
            log.warn("[BILLING-OVERRUN] 实际用量超出预扣，按预扣封顶结算：order_no={} reserved={} actual={} totalTokens={}",
                    handle.orderNo(), handle.reservedCredit(), actual, totalTokens);
            return handle.reservedCredit();
        }
        return actual;
    }

    private boolean enabled() {
        return blogAiProperties.getBilling().isEnabled();
    }

    private long creditPer1kTokens() {
        return blogAiProperties.getBilling().getCreditPer1kTokens();
    }

    private long expireMinutes() {
        return blogAiProperties.getBilling().getReserveExpireMinutes();
    }

    private static long ceilDiv(long dividend, long divisor) {
        return (dividend + divisor - 1) / divisor;
    }
}
