package com.hailin.blogsystem.entity.vo;

import lombok.Data;

/**
 * 抢购结果（grab 的即时返回 / result 轮询的返回，共用一套取值）。
 *
 * <p><b>{@code QUEUED} 和 {@code GRANTED} 必须分清</b>：Redis 预占成功只代表
 * 排上了队、名额占住了，DB 还没入账。UI 上把这两者混为一谈，用户就会看到
 * 「抢到了」然后额度迟迟不到（设计稿 §6.3）。
 */
@Data
public class SeckillResultVO {

    /** 见下方常量 */
    private String status;

    /** 给用户看的一句话；成功路径为空 */
    private String message;

    /** 仅 {@link #GRANTED} 时有值：实际到账的额度 */
    private Long creditAmount;

    /** 没参与过（或 Redis 里查不到预占记录） */
    public static final String NONE = "NONE";

    /** 已预占名额、等待异步入账——<b>不是「抢到了」</b> */
    public static final String QUEUED = "QUEUED";

    /** 已入账，额度已在钱包里 */
    public static final String GRANTED = "GRANTED";

    /** 业务失败：DB 说已售罄。终态，重试不会有不同结果 */
    public static final String FAILED = "FAILED";

    /** 技术失败重试超限，需要人工介入 */
    public static final String FAILED_RETRY = "FAILED_RETRY";

    /* 下面是 grab 被当场拒绝时的理由，不会出现在 result 里 */

    public static final String SOLD_OUT = "SOLD_OUT";
    public static final String DUPLICATE = "DUPLICATE";
    public static final String NOT_ACTIVE = "NOT_ACTIVE";

    public static SeckillResultVO of(String status, String message) {
        SeckillResultVO vo = new SeckillResultVO();
        vo.setStatus(status);
        vo.setMessage(message);
        return vo;
    }

    public static SeckillResultVO granted(long creditAmount) {
        SeckillResultVO vo = of(GRANTED, null);
        vo.setCreditAmount(creditAmount);
        return vo;
    }
}
