package com.hailin.blogsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 秒杀活动。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §2⑤ / §6.4
 *
 * <p><b>{@code soldCount} 是唯一的权威账本</b>：Redis 里的库存只负责挡流量，
 * 最终裁决是条件更新 {@code WHERE sold_count < total_stock}。
 * 所以预热时往 Redis 灌的必须是 {@code totalStock - soldCount} 而不是 {@code totalStock}
 * ——否则服务重启一次就凭空多发一批名额（设计稿 §6.4 的坑）。
 */
@Data
@TableName("seckill_activity")
public class SeckillActivity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    /** 总库存 */
    private Integer totalStock;

    /** DB 侧最终账本：已发放数 */
    private Integer soldCount;

    /** 抢到发多少额度（credit） */
    private Long creditAmount;

    private LocalDateTime startAt;
    private LocalDateTime endAt;

    /** DRAFT / ACTIVE / ENDED */
    private String status;

    private LocalDateTime createdAt;
}
