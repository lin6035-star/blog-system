package com.hailin.blogsystem;

import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.SeckillActivity;
import com.hailin.blogsystem.entity.SeckillOrder;
import com.hailin.blogsystem.mapper.SeckillActivityMapper;
import com.hailin.blogsystem.mapper.SeckillOrderMapper;
import com.hailin.blogsystem.service.SeckillSettleService;
import com.hailin.blogsystem.service.WalletService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 秒杀入账：DB 最终裁决 + 失败分类。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §6.4 / §6.5
 *
 * <p>这类用例不碰 Redis——{@code settle} 是完全的 DB 事务，
 * 它的正确性不该依赖 Redis 是否可用（Redis 只是挡流量的门卫）。
 *
 * <p>三条断言对应设计稿里三个最容易被「顺手改坏」的点：
 * <ul>
 *   <li>重投不能重复加钱（at-least-once 的必然要求）</li>
 *   <li>售罄必须落 FAILED 终态——不落的话这条事件会被无限重投，永远出不来</li>
 *   <li>售罄不能退库存——名额已经给出去了，收回来再发别人只会更乱</li>
 * </ul>
 */
@SpringBootTest
class SeckillSettleTests {

    @Autowired
    private SeckillSettleService seckillSettleService;

    @Autowired
    private SeckillActivityMapper seckillActivityMapper;

    @Autowired
    private SeckillOrderMapper seckillOrderMapper;

    @Autowired
    private WalletService walletService;

    private final long userBase = 9_700_000_000_000L + System.nanoTime() % 1_000_000_000L;

    @Test
    void settleGrantsCreditAndRecordsOrder() {
        SeckillActivity activity = newActivity(1);
        long userId = userBase;

        seckillSettleService.settle(activity.getId(), userId);

        SeckillOrder order = seckillOrderMapper.selectByUserAndActivity(userId, activity.getId());
        assertThat(order).isNotNull();
        assertThat(order.getStatus()).isEqualTo(BlogConstants.Seckill.ORDER_GRANTED);
        assertThat(walletService.getWallet(userId).getBalance())
                .isEqualTo(activity.getCreditAmount());
        assertThat(seckillActivityMapper.selectById(activity.getId()).getSoldCount())
                .as("DB 侧账本要跟着 +1")
                .isEqualTo(1);
    }

    /** at-least-once 的必然要求：同一条事件被重投时，不能再加一次钱、扣一次库存 */
    @Test
    void settleIsIdempotentWhenEventRedelivered() {
        SeckillActivity activity = newActivity(5);
        long userId = userBase + 1;

        seckillSettleService.settle(activity.getId(), userId);
        seckillSettleService.settle(activity.getId(), userId);

        assertThat(walletService.getWallet(userId).getBalance())
                .as("重投不能再加一次钱")
                .isEqualTo(activity.getCreditAmount());
        assertThat(seckillActivityMapper.selectById(activity.getId()).getSoldCount())
                .as("重投不能再扣一次库存")
                .isEqualTo(1);
    }

    /**
     * 业务失败（DB 说售罄）：落 FAILED 终态、不发钱。
     *
     * <p>这个终态不是「记录一下」，而是**让消费者能安全 ACK 的依据**——
     * 没有它，售罄事件会被无限重投，每轮都走一遍同样的分支，永远出不来。
     */
    @Test
    void soldOutMarksFailedWithoutTouchingWallet() {
        SeckillActivity activity = newActivity(1);
        // 把 DB 口径的库存吃满：Redis 那边可能还认为有货，这正是「Redis 多发」的场景
        seckillActivityMapper.increaseSoldCount(activity.getId());

        long userId = userBase + 2;
        seckillSettleService.settle(activity.getId(), userId);

        SeckillOrder order = seckillOrderMapper.selectByUserAndActivity(userId, activity.getId());
        assertThat(order.getStatus()).isEqualTo(BlogConstants.Seckill.ORDER_FAILED);
        assertThat(walletService.getWallet(userId).getBalance())
                .as("售罄的用户一分都不该拿到")
                .isZero();
        assertThat(seckillActivityMapper.selectById(activity.getId()).getSoldCount())
                .as("**不能把库存退回去**——名额已经给出去了，收回来再发别人只会更乱")
                .isEqualTo(1);
    }

    /**
     * 死信：订单还不存在时也要落下终态。
     *
     * <p>不补这条记录的话，这个用户在对账眼里永远是「在途」，
     * 而且 {@code uk_user_activity} 也没被占住——他可以再抢一次并真的拿到额度。
     */
    @Test
    void deadLetterLandsTerminalStateEvenWithoutExistingOrder() {
        SeckillActivity activity = newActivity(1);
        long userId = userBase + 3;

        seckillSettleService.markDeadLetter(activity.getId(), userId);

        SeckillOrder order = seckillOrderMapper.selectByUserAndActivity(userId, activity.getId());
        assertThat(order).isNotNull();
        assertThat(order.getStatus()).isEqualTo(BlogConstants.Seckill.ORDER_FAILED_RETRY);
    }

    /** 已入账的订单不能被后来的一次失败重试改写成死信——那是既成事实 */
    @Test
    void deadLetterNeverOverwritesGrantedOrder() {
        SeckillActivity activity = newActivity(1);
        long userId = userBase + 4;

        seckillSettleService.settle(activity.getId(), userId);
        seckillSettleService.markDeadLetter(activity.getId(), userId);

        SeckillOrder order = seckillOrderMapper.selectByUserAndActivity(userId, activity.getId());
        assertThat(order.getStatus()).isEqualTo(BlogConstants.Seckill.ORDER_GRANTED);
        assertThat(walletService.getWallet(userId).getBalance())
                .isEqualTo(activity.getCreditAmount());
    }

    // ---------- helpers ----------

    private SeckillActivity newActivity(int totalStock) {
        SeckillActivity created = new SeckillActivity();
        created.setName("入账测试-" + System.nanoTime());
        created.setTotalStock(totalStock);
        created.setSoldCount(0);
        created.setCreditAmount(1000L);
        created.setStartAt(LocalDateTime.now().minusMinutes(5));
        created.setEndAt(LocalDateTime.now().plusMinutes(30));
        created.setStatus(BlogConstants.Seckill.ACTIVITY_ACTIVE);
        seckillActivityMapper.insert(created);
        return created;
    }
}
