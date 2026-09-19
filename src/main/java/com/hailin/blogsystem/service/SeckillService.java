package com.hailin.blogsystem.service;

import com.hailin.blogsystem.entity.vo.SeckillActivityVO;
import com.hailin.blogsystem.entity.vo.SeckillResultVO;

import java.util.List;

/**
 * 秒杀：Redis 预占 + DB 最终裁决。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §6
 *
 * <p><b>抢购返回的是「排队中」，不是「抢到了」</b>：预占成功只说明名额占住了、
 * 事件已入队，真正的发放由消费者落库。确定性结论要靠轮询 {@link #getResult}
 * ——这一步不能省，否则「说好抢到了、额度却没到」就无解了。
 *
 * <p>分层是刻意的：Redis 挡流量（O(QPS) → O(库存)），DB 保正确
 * （条件更新引用 {@code total_stock} 列自裁）。反过来让 Redis 当裁判，
 * 一旦 Redis 重建时口径取错就会超发。
 */
public interface SeckillService {

    /** 活动列表。{@code userId} 为 null 表示游客，{@code myStatus} 一律 NONE */
    List<SeckillActivityVO> listActivities(Long userId);

    /**
     * 抢购。
     *
     * <p>Redis Lua 一次原子完成：类型预检 → 状态/时间窗判定 → 查重 → 发事件 →
     * 扣库存 → 记名单。分开执行会在「判断」和「扣减」之间被插队，直接超卖。
     *
     * @return 取值见 {@link SeckillResultVO}
     */
    SeckillResultVO grab(Long activityId, Long userId);

    /** 查结果（前端轮询用）。 */
    SeckillResultVO getResult(Long activityId, Long userId);

    /**
     * 预热：把 DB 的活动状态重建到 Redis（库存 / 已抢名单 / 元数据）。
     *
     * <p>幂等，可重复调用——刻意不做「增量 SADD」：{@code users} 集合的权威来源是
     * {@code seckill_order} 里 GRANTED 的记录，只增不减会让被人工删掉的订单在 Redis 里
     * 留下幽灵名额，那个用户从此永远抢不到。所以每次预热都是<b>先清后建</b>。
     *
     * @param activityId 为 null 时预热全部 ACTIVE 活动
     * @return 实际预热的活动数
     */
    int preheat(Long activityId);
}
