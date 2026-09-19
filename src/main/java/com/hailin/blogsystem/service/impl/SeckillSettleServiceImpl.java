package com.hailin.blogsystem.service.impl;

import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.SeckillActivity;
import com.hailin.blogsystem.entity.SeckillOrder;
import com.hailin.blogsystem.mapper.SeckillActivityMapper;
import com.hailin.blogsystem.mapper.SeckillOrderMapper;
import com.hailin.blogsystem.service.SeckillSettleService;
import com.hailin.blogsystem.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 秒杀入账实现：DB 是最终裁决者。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §6.4 / §6.5
 *
 * <p><b>为什么 Redis 不能当裁判</b>：Redis 挂了之后重新预热，只要口径取错
 * （灌总库存而不是「总库存 − DB 已发放」）就直接超发。所以库存的权威值是
 * {@code seckill_activity.sold_count}，Redis 只是挡流量的门卫。
 *
 * <p><b>代价比想象中小</b>：重复请求和售罄请求已经在 Lua 里被挡掉了，
 * 能走到这里的只有中签者——<b>DB 压力是 O(库存)，不是 O(QPS)</b>，削峰没有失效。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SeckillSettleServiceImpl implements SeckillSettleService {

    /** 钱包流水类型 / 账单业务类型 */
    private static final String SECKILL_GRANT = "SECKILL_GRANT";
    private static final String SECKILL_ORDER = "SECKILL_ORDER";

    private final SeckillActivityMapper seckillActivityMapper;
    private final SeckillOrderMapper seckillOrderMapper;
    private final WalletService walletService;

    @Override
    @Transactional
    public void settle(Long activityId, Long userId) {
        // 1. 先查已存在状态。这是 at-least-once 语义下重投幂等的**唯一正确做法**：
        //    靠「插入时撞唯一键再吞掉冲突」会把「上次已经成功了」和「这次真的冲突了」
        //    混成同一条路径，而这俩的后续处置完全不同
        SeckillOrder existing = seckillOrderMapper.selectByUserAndActivity(userId, activityId);
        if (existing != null) {
            log.debug("[SECKILL] 事件重投，订单已是终态 {}：activityId={} userId={}",
                    existing.getStatus(), activityId, userId);
            return;
        }

        SeckillActivity activity = seckillActivityMapper.selectById(activityId);
        if (activity == null) {
            // 活动不该消失，消失本身就是异常 → 按技术失败处理，交给重试与死信兜底
            throw new IllegalStateException("秒杀活动不存在：activityId=" + activityId);
        }

        // 2. 最终裁决。条件里引用 total_stock **列**，不接收调用方传来的总库存
        if (seckillActivityMapper.increaseSoldCount(activityId) == 0) {
            // 业务失败：Redis 多发了名额（在途事件 + 预热窗口都可能造成）。
            // **不要把库存退回去**——名额已经给出去了，收回来再发别人只会更乱。
            // 落 FAILED 终态后**正常返回 = ACK**：售罄是永久状态，
            // 不 ACK 的话这条事件会被无限重投，每轮都走一遍同样的分支，永远出不来
            insertOrder(activityId, userId, activity.getCreditAmount(),
                    BlogConstants.Seckill.ORDER_FAILED);
            log.error("[SECKILL-OVERSOLD] 活动 {} 名额已发完，用户 {} 落 FAILED"
                    + "（Redis 多发了一个名额，需对账确认差额）", activityId, userId);
            return;
        }

        // 3. 入账。与扣库存**同一事务**：任何一步失败，sold_count 一起回滚，
        //    不会留下「账上少一份、钱没发出去」的中间态
        long credit = activity.getCreditAmount();
        try {
            walletService.increase(userId, credit, SECKILL_GRANT, SECKILL_ORDER,
                    String.valueOf(activityId), walletIdempotencyKey(activityId, userId), "秒杀到账");
        } catch (DuplicateKeyException e) {
            // 流水幂等键撞了 = 这个用户在这场活动里的额度**以前发过一次**。
            // 正常流程走不到这里（`uk_user_activity` 会先在上一句的订单查询里挡住），
            // 能走到就说明有人删了 seckill_order 却没删 wallet_transaction
            // ——流水是账本删不得，所以只可能是「重置活动时没清干净」。
            // 堆栈里只有一个唯一键冲突，完全指不到这个根因，这里替它点出来。
            log.error("[SECKILL-DIRTY-RESET] 活动 {} 用户 {} 的流水已存在，"
                    + "疑似用 DELETE seckill_order 重置过活动。请改用「新建一场活动」，"
                    + "重置旧活动会让历史订单与账本对不上", activityId, userId);
            throw e;
        }
        insertOrder(activityId, userId, credit, BlogConstants.Seckill.ORDER_GRANTED);

        log.info("[SECKILL] 入账成功 activityId={} userId={} credit={}", activityId, userId, credit);
    }

    @Override
    @Transactional
    public void markDeadLetter(Long activityId, Long userId) {
        if (seckillOrderMapper.markTerminal(userId, activityId,
                BlogConstants.Seckill.ORDER_FAILED_RETRY) == 0) {
            // 没更新到有两种可能，处置完全相反：
            //   ① 订单还不存在（失败发生在插入之前）→ 补一条终态记录。
            //      不补的话这个用户在对账眼里永远是「在途」，而且 uk_user_activity
            //      也没被占住——他可以再抢一次并真的拿到额度。
            //   ② 已有 GRANTED 订单（既成事实）→ **不能改写**，跳过。
            // 把两者当成一种就会去补插一条并撞上唯一索引，整条死信路径直接失败
            SeckillOrder existing = seckillOrderMapper.selectByUserAndActivity(userId, activityId);
            if (existing != null) {
                log.warn("[SECKILL] 活动 {} 用户 {} 已是 {}，死信标记跳过（不覆盖既成事实）",
                        activityId, userId, existing.getStatus());
                return;
            }
            insertOrder(activityId, userId, 0L, BlogConstants.Seckill.ORDER_FAILED_RETRY);
        }
        log.error("[SECKILL-DEAD-LETTER] 活动 {} 用户 {} 入账重试超限，"
                + "置 FAILED_RETRY 待人工处理（改回待处理 + 重投新事件，不要复用旧消息 ID）",
                activityId, userId);
    }

    private void insertOrder(Long activityId, Long userId, long creditAmount, String status) {
        SeckillOrder order = new SeckillOrder();
        order.setActivityId(activityId);
        order.setUserId(userId);
        order.setCreditAmount(creditAmount);
        order.setStatus(status);
        seckillOrderMapper.insert(order);
    }

    /**
     * 钱包记账幂等键。
     *
     * <p>**必须由 (activityId, userId) 确定性地派生**，不能每次重试随机生成
     * ——随机键在重投时挡不住重复加钱，而重投恰恰是这套机制的常态。
     */
    private static String walletIdempotencyKey(Long activityId, Long userId) {
        return "SECKILL:" + activityId + ":" + userId;
    }
}
