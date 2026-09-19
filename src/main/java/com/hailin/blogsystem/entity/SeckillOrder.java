package com.hailin.blogsystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 秒杀订单（一人一单）。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §2⑤ / §6.5
 *
 * <p>{@code uk_user_activity} 是一人一单的**最终防线**：Redis 的 Set 只挡住绝大多数
 * 重复点击，DB 唯一索引负责兜住漏网的那一个。
 *
 * <p><b>三个终态不能混</b>（§6.5）：
 * <ul>
 *   <li>{@code GRANTED}——已入账</li>
 *   <li>{@code FAILED}——业务失败（DB 说售罄）。重投不会有不同结果，所以要 ACK</li>
 *   <li>{@code FAILED_RETRY}——技术失败重试超限的死信终态</li>
 * </ul>
 * 没有第三个状态的话，「技术失败重试中」和「消费者崩了」在 PEL 里长得一模一样，
 * 对账无法区分二者。
 */
@Data
@TableName("seckill_order")
public class SeckillOrder {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long activityId;
    private Long userId;

    /** 到账额度（下单时快照，活动改额度不影响已发出的） */
    private Long creditAmount;

    /** GRANTED / FAILED / FAILED_RETRY */
    private String status;

    private LocalDateTime createdAt;
}
