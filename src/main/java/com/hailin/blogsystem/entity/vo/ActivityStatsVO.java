package com.hailin.blogsystem.entity.vo;

/**
 * 站点活跃统计（第三刀 · Bitmap 位图）。
 *
 * @param todayActive            今日活跃用户数
 * @param yesterdayActive        昨日活跃用户数
 * @param weekActive             近 7 天活跃用户数——是**去重后的总人数**，不是 7 天之和
 *                               （Bitmap 的 OR 天然去重，这是它相对"每天一个 Set"的另一个优势）
 * @param retainedFromYesterday  昨天活跃、今天仍然活跃的人数
 * @param yesterdayRetentionRate 昨日留存率 = {@code retainedFromYesterday / yesterdayActive}。
 *                               **昨日无人活跃时为 null**，不是 0——
 *                               "算不出来"和"一个都没留住"是两回事，前者不能拿来当指标看
 */
public record ActivityStatsVO(
        long todayActive,
        long yesterdayActive,
        long weekActive,
        long retainedFromYesterday,
        Double yesterdayRetentionRate
) {
}
