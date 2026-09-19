package com.hailin.blogsystem.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.constants.SeckillKeys;
import com.hailin.blogsystem.entity.SeckillActivity;
import com.hailin.blogsystem.entity.SeckillOrder;
import com.hailin.blogsystem.mapper.SeckillActivityMapper;
import com.hailin.blogsystem.mapper.SeckillOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 秒杀对账（设计稿 §7.1）。
 *
 * <p><b>判据是「最终收敛」，不是「相等」</b>——这是这个任务最值得讲的一点。
 * 异步入账天然有延迟，任何一个瞬间 Redis 预占数都合法地大于 DB 已入账数。
 * 「不等就告警」会在一场正常活动里刷出成百上千条假警报，然后就没人看了。
 * 所以这里看的是**连续 N 轮趋势**：单轮不一致只记 warn，连续不收敛才 error。
 *
 * <p>同时查正负两个方向：
 * <ul>
 *   <li>预占数 <b>多于</b>「已入账 + 在途」→ 有事件丢了或卡死了</li>
 *   <li>预占数 <b>少于</b> DB 订单数 → Redis 状态丢失，或重建时口径取错了</li>
 *   <li>名单增量 ≠ 库存减量 → Lua 部分失败，或 key 被外部改坏</li>
 * </ul>
 * 只查一个方向的话，另一类故障会安静地积累到无法收口。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SeckillReconcileTask {

    /** 连续几轮不收敛才升级成告警（5 分钟一轮 → 15 分钟） */
    private static final int ALERT_AFTER_ROUNDS = 3;

    /** 每个活动连续异常了几轮。进程内状态，重启后重新计数——对账不需要跨重启的连续性 */
    private final Map<Long, Integer> abnormalRounds = new ConcurrentHashMap<>();

    private final StringRedisTemplate redisTemplate;
    private final SeckillActivityMapper seckillActivityMapper;
    private final SeckillOrderMapper seckillOrderMapper;

    @Scheduled(fixedDelay = 5 * 60 * 1000L)
    public void reconcile() {
        List<SeckillActivity> activities = seckillActivityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivity>()
                        .eq(SeckillActivity::getStatus, BlogConstants.Seckill.ACTIVITY_ACTIVE));

        for (SeckillActivity activity : activities) {
            try {
                reconcileOne(activity);
            } catch (RuntimeException e) {
                log.warn("[SECKILL] 活动 {} 对账失败", activity.getId(), e);
            }
        }
    }

    private void reconcileOne(SeckillActivity activity) {
        Long id = activity.getId();
        String usersKey = SeckillKeys.users(id);
        String stockKey = SeckillKeys.stock(id);
        String eventsKey = SeckillKeys.events(id);

        Long reserved = redisTemplate.opsForSet().size(usersKey);
        String stockRaw = redisTemplate.opsForValue().get(stockKey);
        if (reserved == null || stockRaw == null) {
            // key 不存在 = 这个活动还没预热过，不是不一致
            return;
        }

        long stock = parseLong(stockRaw, -1L);
        long ordered = seckillOrderMapper.selectCount(
                new LambdaQueryWrapper<SeckillOrder>().eq(SeckillOrder::getActivityId, id));
        long pending = pendingCount(eventsKey);

        List<String> problems = new ArrayList<>();

        long inFlight = reserved - ordered;
        if (inFlight < 0) {
            problems.add(String.format("Redis 预占数(%d) 少于 DB 订单数(%d)——Redis 状态丢失，"
                    + "或重建时库存口径取成了总库存", reserved, ordered));
        } else if (inFlight > pending) {
            problems.add(String.format("在途(%d) 多于 PEL 待处理(%d)——有预占没有对应的待处理消息，"
                    + "事件可能已经丢失", inFlight, pending));
        }

        // 抢购脚本里 SADD 和 DECR 是一次原子操作，所以名单增量必然等于库存减量。
        // 对不上说明 Lua 中途失败了，或者有人手改过 key
        List<Object> bases = redisTemplate.opsForHash()
                .multiGet(SeckillKeys.meta(id), List.of("reservedBase", "stockBase"));
        if (bases != null && bases.size() == 2 && bases.get(0) != null && bases.get(1) != null) {
            long reservedBase = parseLong(String.valueOf(bases.get(0)), reserved);
            long stockBase = parseLong(String.valueOf(bases.get(1)), stock);
            long reservedDelta = reserved - reservedBase;
            long stockDelta = stockBase - stock;
            if (reservedDelta != stockDelta) {
                problems.add(String.format("名单增量(%d) 不等于库存减量(%d)——Lua 部分失败，"
                        + "或 key 被外部改坏", reservedDelta, stockDelta));
            }
        }

        if (problems.isEmpty()) {
            abnormalRounds.remove(id);
            return;
        }
        recordAbnormal(id, String.join("；", problems));
    }

    private void recordAbnormal(Long activityId, String problem) {
        int rounds = abnormalRounds.merge(activityId, 1, Integer::sum);
        if (rounds >= ALERT_AFTER_ROUNDS) {
            log.error("[SECKILL-RECONCILE] 活动 {} 连续 {} 轮不收敛，需要人工介入：{}",
                    activityId, rounds, problem);
        } else {
            // 单轮不一致是异步落库的正常形态，只记 warn——判据是收敛趋势，不是某一刻的相等
            log.warn("[SECKILL-RECONCILE] 活动 {} 第 {} 轮不一致（未达告警阈值）：{}",
                    activityId, rounds, problem);
        }
    }

    /**
     * PEL 待处理条数（XPENDING 的 summary 形式：{@code [count, minId, maxId, consumers]}）。
     *
     * <p>NOGROUP 要吞掉：活动刚预热、还没人抢过的时候，events key 和消费组都还不存在，
     * 这时 pending 就是 0——它是「还没开始」而不是「不一致」。
     * 让它抛出去的话，这一轮对账会在这里中断，后面两条检查全都跑不到。
     */
    private long pendingCount(String eventsKey) {
        Object raw;
        try {
            raw = redisTemplate.execute((RedisCallback<Object>) connection ->
                    connection.execute("XPENDING",
                            eventsKey.getBytes(StandardCharsets.UTF_8),
                            RedisConstants.SECKILL_CONSUMER_GROUP.getBytes(StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            return 0;
        }
        if (raw instanceof List<?> summary && !summary.isEmpty()
                && summary.get(0) instanceof Number count) {
            return count.longValue();
        }
        return 0;
    }

    private static long parseLong(String raw, long fallback) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
