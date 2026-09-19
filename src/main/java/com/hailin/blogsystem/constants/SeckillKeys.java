package com.hailin.blogsystem.constants;

/**
 * 秒杀 Redis key 的拼装（设计稿 docs/redis/钱包与秒杀计划.md §6.1）。
 *
 * <pre>
 * seckill:{activityId}:stock    String   剩余库存
 * seckill:{activityId}:users    Set      已抢到的 userId（一人一单的快速路径）
 * seckill:{activityId}:meta     Hash     status / startAt / endAt（时间统一用**秒**，与 TIME 对齐）
 * seckill:{activityId}:events   Stream   待入账事件（消费组 seckill-settle）
 * </pre>
 *
 * <p>四个 key 共用 {@code {activityId}} 这个 hash tag：同一段 Lua 里的多个 key 在
 * Redis Cluster 下必须落在同一个槽。当前是单机 Redis 用不上，但现在统一命名几乎零成本。
 *
 * <p><b>为什么单独成一个类、而不是散在各处拼字符串</b>：{@code SeckillGrabScript}
 * 里的 {@code KEYS[1..4]} 顺序与这里的四个方法**必须严格对应**，放一处才能一眼对平；
 * 散开写早晚会出现「Lua 读 KEYS[3] 当 meta、Java 传的是 events」这类不对齐，
 * 而那会表现为一个极其难查的 WRONGTYPE。
 */
public final class SeckillKeys {

    /** 剩余库存。预热时灌的是 {@code totalStock - soldCount}，不是 {@code totalStock} */
    public static String stock(Long activityId) {
        return prefix(activityId) + "stock";
    }

    /** 已抢到的 userId 集合——挡住重复点击的快速路径（最终防线是 DB 的 uk_user_activity） */
    public static String users(Long activityId) {
        return prefix(activityId) + "users";
    }

    /** 活动元数据 Hash：status / startAt / endAt。时间用**秒**，因为要和 Redis 的 TIME 比 */
    public static String meta(Long activityId) {
        return prefix(activityId) + "meta";
    }

    /** 待入账事件 Stream。⚠️ 预热时**不能删它**——里面是已预占、还没入账的名额 */
    public static String events(Long activityId) {
        return prefix(activityId) + "events";
    }

    private static String prefix(Long activityId) {
        return RedisConstants.SECKILL_KEY_PREFIX + "{" + activityId + "}:";
    }

    private SeckillKeys() {
    }
}
