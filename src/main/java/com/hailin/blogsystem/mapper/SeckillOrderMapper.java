package com.hailin.blogsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hailin.blogsystem.entity.SeckillOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 秒杀订单 Mapper。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §2⑤ / §6.5
 */
@Mapper
public interface SeckillOrderMapper extends BaseMapper<SeckillOrder> {

    /**
     * 按 (user_id, activity_id) 取订单——{@code uk_user_activity} 决定的唯一性。
     *
     * <p>消费者每处理一条事件都要先查一次：<b>查到 GRANTED / FAILED 就直接 ACK</b>。
     * 这不是优化，是 at-least-once 语义下重投幂等的唯一正确做法——
     * 靠「插入时撞唯一键再吞掉冲突」来判断，会把「上次成功了」和「这次冲突了」混在一起。
     */
    @Select("""
            SELECT id, activity_id, user_id, credit_amount, status, created_at
            FROM seckill_order
            WHERE user_id = #{userId}
              AND activity_id = #{activityId}
            """)
    SeckillOrder selectByUserAndActivity(@Param("userId") Long userId,
                                         @Param("activityId") Long activityId);

    /**
     * 把订单推进到终态。
     *
     * <p>带 {@code status <> GRANTED} 条件：已入账的订单是既成事实，
     * 不能被后来的一次失败重试改写成 FAILED_RETRY。
     */
    @Update("""
            UPDATE seckill_order
            SET status = #{status}
            WHERE user_id = #{userId}
              AND activity_id = #{activityId}
              AND status <> 'GRANTED'
            """)
    int markTerminal(@Param("userId") Long userId,
                     @Param("activityId") Long activityId,
                     @Param("status") String status);
}
