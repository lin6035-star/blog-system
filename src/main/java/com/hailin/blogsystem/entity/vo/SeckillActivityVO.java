package com.hailin.blogsystem.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 秒杀活动（列表展示用）。
 *
 * <p>库存展示走 <b>DB 口径</b>（{@code totalStock - soldCount}），不是 Redis 口径。
 * Redis 那个数只是挡流量的门卫，会因为在途事件、预热窗口而短暂偏离；
 * 用户看到的「还剩 N 份」应当以已经真正发出去的为准。
 */
@Data
public class SeckillActivityVO {

    private Long id;
    private String name;

    private Integer totalStock;

    /** 已发放（DB 最终账本） */
    private Integer soldCount;

    /** 剩余可抢 = totalStock - soldCount */
    private Integer remainingStock;

    /** 抢到发多少额度（credit） */
    private Long creditAmount;

    private LocalDateTime startAt;
    private LocalDateTime endAt;

    /** DRAFT / ACTIVE / ENDED */
    private String status;

    /** 当前登录用户的参与状态，见 {@link SeckillResultVO}；游客为 NONE */
    private String myStatus;
}
