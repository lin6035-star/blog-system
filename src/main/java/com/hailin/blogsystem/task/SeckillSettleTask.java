package com.hailin.blogsystem.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.component.SeckillPreheatedEvent;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.constants.SeckillKeys;
import com.hailin.blogsystem.entity.SeckillActivity;
import com.hailin.blogsystem.mapper.SeckillActivityMapper;
import com.hailin.blogsystem.service.SeckillSettleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 秒杀入账消费者。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §6.3 / §6.5
 *
 * <p><b>为什么是 Stream 而不是复用 {@code RagRetryQueue}</b>：那个延迟队列的
 * {@code takeDue} 是「取走即删」，取出后进程崩溃任务就永久消失。RAG 能接受
 * （有失败标记 + 人工修复入口兜底），额度入账没有这层——它要的是 <b>at-least-once</b>，
 * 而那正好是 Stream 消费组已经做好的事。
 *
 * <p><b>为什么用轮询而不是 {@code StreamMessageListenerContainer}</b>：项目零 Stream 先例，
 * 自建容器要配线程池和生命周期，收益不匹配；非阻塞 {@code COUNT 10} 是毫秒级操作。
 *
 * <p>⚠️ <b>绝不能用 {@code BLOCK}</b>：调度线程池 {@code pool.size=1} 是<b>串行</b>的
 * （{@code ArticleViewCountSyncTask} 依赖这一点保证两个任务不并发），
 * 一个阻塞读会把同池的浏览量同步 / RAG 重试 / 计费单过期全部卡死。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SeckillSettleTask {

    /** 消费组名：所有消费者实例共用，多实例时靠它分片 */
    private static final String GROUP = RedisConstants.SECKILL_CONSUMER_GROUP;

    /**
     * 消费者名。
     *
     * <p>⚠️ <b>多实例部署时必须改成实例唯一的</b>（比如 hostname / Pod 名），
     * 否则两个实例共用同一个消费者名，PEL 里的消息归属就分不清了
     * ——「谁在处理这条」会变成一笔糊涂账。
     */
    private static final String CONSUMER = "consumer-1";

    /** 每轮每个活动最多处理多少条，避免一轮把调度线程占太久 */
    private static final int BATCH = 10;

    /** 超过这么久没被 ACK 的消息才允许接管（给正常处理留足时间） */
    private static final long CLAIM_MIN_IDLE_MILLIS = 60_000L;

    /** 投递次数上限：到这里停止重试，落 FAILED_RETRY 死信 */
    private static final int MAX_DELIVERY = 5;

    /** ACTIVE 活动列表的缓存时长，见 {@link #currentActivityIds()} */
    private static final long ACTIVITY_REFRESH_MILLIS = 60_000L;

    /** ACTIVE 活动 id 缓存。调度线程池是串行的，本无并发，加 volatile 只为将来调大池子时不埋雷 */
    private volatile List<Long> activeActivityIds = List.of();
    private volatile long activityIdsRefreshedAt = 0L;

    private final StringRedisTemplate redisTemplate;
    private final SeckillActivityMapper seckillActivityMapper;
    private final SeckillSettleService seckillSettleService;

    /**
     * 每 2 秒捞一轮。结果通常在百毫秒内产出，这个频率足够让用户感觉「秒到账」。
     *
     * <p>用 {@code fixedDelay} 而不是 {@code fixedRate}：这是批处理兜底，
     * 上一轮没跑完不该叠下一轮（与 {@code AiBillingExpireTask} 同一取舍）。
     */
    @Scheduled(fixedDelay = 2000)
    public void consume() {
        for (Long activityId : currentActivityIds()) {
            try {
                settleActivity(activityId);
            } catch (RuntimeException e) {
                // 单个活动出问题不拖垮其他活动
                log.warn("[SECKILL] 活动 {} 的入账队列本轮处理失败", activityId, e);
            }
        }
    }

    /**
     * 当前 ACTIVE 的活动 id，**带缓存**。
     *
     * <p>⚠️ 不能每轮都查 DB：这个任务每 2 秒跑一次，直接查就是每天 4 万多次查询，
     * 外加同样数量的 MyBatis DEBUG 日志——日志被刷得没法看，真正有用的告警
     * 反而淹在里面。最初写的是「活动表很小，这个查询可以忽略」，
     * 忽略了成本不在 DB 而在**日志**，是实际跑起来才暴露的。
     *
     * <p>缓存 1 分钟：活动列表变化很慢，但太长的话「新活动开了却没人消费」
     * 要拖很久才自愈。60 秒是「日志不吵」和「自愈够快」之间的折中。
     */
    /**
     * 预热完成后清掉缓存，让新预热的活动**立刻**被消费。
     *
     * <p>没有这一步的话，「预热完等活动被消费」最长要 60 秒——用户那边看到的就是
     * 「抢到了但一直排队中」。预热是活动的必经路径，所以这个钩子覆盖了正常流程。
     */
    @EventListener(SeckillPreheatedEvent.class)
    public void onPreheated() {
        activityIdsRefreshedAt = 0L;
    }

    private List<Long> currentActivityIds() {
        long now = System.currentTimeMillis();
        if (now - activityIdsRefreshedAt < ACTIVITY_REFRESH_MILLIS) {
            return activeActivityIds;
        }
        activeActivityIds = seckillActivityMapper.selectList(
                        new LambdaQueryWrapper<SeckillActivity>()
                                .eq(SeckillActivity::getStatus, BlogConstants.Seckill.ACTIVITY_ACTIVE))
                .stream()
                .map(SeckillActivity::getId)
                .toList();
        activityIdsRefreshedAt = now;
        return activeActivityIds;
    }

    private void settleActivity(Long activityId) {
        String eventsKey = SeckillKeys.events(activityId);
        ensureGroup(eventsKey);
        drainNewMessages(eventsKey);
        reclaimStaleMessages(eventsKey);
    }

    /**
     * 确保消费组存在（幂等）。
     *
     * <p>{@code MKSTREAM} 不能省：stream 不存在时如果不建，首轮永远创建不出组，
     * 入账队列就一直是空的——而预热时我们**刻意不清 events**，
     * 所以这个 stream 正常是由抢购脚本里的 XADD 创建的。
     */
    private void ensureGroup(String eventsKey) {
        try {
            exec("XGROUP", "CREATE", eventsKey, GROUP, "0", "MKSTREAM");
        } catch (RuntimeException e) {
            // BUSYGROUP = 组已存在，是绝大多数情况下的正常路径。
            // 其他异常也吞掉：下一轮还会再来，不该因为建组失败把整轮任务掀了
            if (!String.valueOf(e.getMessage()).contains("BUSYGROUP")) {
                log.debug("[SECKILL] 创建消费组失败（下一轮重试）：{} - {}", eventsKey, e.getMessage());
            }
        }
    }

    /** 读新消息。**非阻塞**——不带 BLOCK，理由见类注释 */
    private void drainNewMessages(String eventsKey) {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                Consumer.from(GROUP, CONSUMER),
                StreamReadOptions.empty().count(BATCH),
                StreamOffset.create(eventsKey, ReadOffset.lastConsumed()));

        if (records == null || records.isEmpty()) {
            return;
        }
        for (MapRecord<String, Object, Object> record : records) {
            // 新消息的投递次数就是 1，不需要额外查询
            handle(eventsKey, record.getId().getValue(), record.getValue(), 1);
        }
    }

    /**
     * 接管超时未确认的消息，并把重试超限的转成死信。
     *
     * <p>用 {@code XPENDING} + {@code XCLAIM} 而不是 {@code XAUTOCLAIM}：前者在更早的
     * Redis 版本上就有，而且 {@code XPENDING} 的扩展形式**直接给出投递次数**——
     * 死信判据靠的正是它，不需要自己维护 attempt 字段（那正是选 Stream 而不是手搓 PEL 的红利）。
     */
    private void reclaimStaleMessages(String eventsKey) {
        List<Object> pending;
        try {
            // 每行是 [id, consumer, idleMillis, deliveryCount]
            pending = exec("XPENDING", eventsKey, GROUP, "-", "+", String.valueOf(BATCH));
        } catch (RuntimeException e) {
            log.debug("[SECKILL] 查询 PEL 失败：{} - {}", eventsKey, e.getMessage());
            return;
        }

        for (Object row : pending) {
            if (!(row instanceof List<?> entry) || entry.size() < 4) {
                continue;
            }
            String recordId = str(entry.get(0));
            long idleMillis = toLong(entry.get(2), 0L);
            long deliveryCount = toLong(entry.get(3), 1L);

            if (idleMillis < CLAIM_MIN_IDLE_MILLIS) {
                continue;   // 还有人在处理它（或刚投递完），别抢
            }

            Map<String, String> fields = claim(eventsKey, recordId);
            if (fields.isEmpty()) {
                continue;   // 已被别人接管或已删除——下一轮自然收敛
            }

            Long activityId = parseLong(fields.get("activityId"));
            Long userId = parseLong(fields.get("userId"));
            if (activityId == null || userId == null) {
                log.error("[SECKILL] 事件字段缺失，丢弃 recordId={} fields={}", recordId, fields);
                ackAndDelete(eventsKey, recordId);
                continue;
            }

            if (deliveryCount >= MAX_DELIVERY) {
                // 到上限了：不再重试，落终态 + 告警，然后 ACK 清出 PEL。
                // 不清的话它永远占着 PEL，对账也分不清「还在重试」和「已经放弃了」
                seckillSettleService.markDeadLetter(activityId, userId);
                ackAndDelete(eventsKey, recordId);
                continue;
            }

            handle(eventsKey, recordId, fields, deliveryCount);
        }
    }

    /** 处理一条事件：正常返回就 ACK，抛异常就留在 PEL 等下一轮回收 */
    private void handle(String eventsKey, String recordId, Map<?, ?> fields, long deliveryCount) {
        Long activityId = parseLong(fields.get("activityId"));
        Long userId = parseLong(fields.get("userId"));
        if (activityId == null || userId == null) {
            log.error("[SECKILL] 事件字段缺失，丢弃 recordId={} fields={}", recordId, fields);
            ackAndDelete(eventsKey, recordId);
            return;
        }

        try {
            seckillSettleService.settle(activityId, userId);
            ackAndDelete(eventsKey, recordId);
        } catch (RuntimeException e) {
            // 技术失败：**不 ACK**，留在 PEL 等回收重试。
            // 此刻不确定 DB 是否已落库（可能提交成功但响应超时），退库存可能造成超发,
            // 唯一安全的做法是重试——靠 uk_user_activity 和流水幂等键兜底
            log.warn("[SECKILL] 入账失败（第 {} 次投递），保留在 PEL 等待重试 activityId={} userId={}",
                    deliveryCount, activityId, userId, e);
        }
    }

    /**
     * ACK 后删除。
     *
     * <p>⚠️ {@code XDEL} 是<b>物理删除，所有消费组一起受影响</b>（设计稿 §6.2）。
     * 当前只有一个消费组所以安全；将来加了对账组 / 监控组之后，
     * 就不能再「谁处理完谁删」，要改成「所有消费组都 ACK 过的才可删」。
     *
     * <p>顺序不能反：先 ACK 再删。反过来的话，两步之间崩溃会留下
     * 「消息没了但还挂在 PEL」的悬空条目；而先 ACK 再删最坏只是残留一条已完成消息。
     */
    private void ackAndDelete(String eventsKey, String recordId) {
        RecordId id = RecordId.of(recordId);
        redisTemplate.opsForStream().acknowledge(eventsKey, GROUP, id);
        redisTemplate.opsForStream().delete(eventsKey, id);
    }

    /** 接管一条消息并返回它的字段；空 Map 表示这条已经不需要我了 */
    private Map<String, String> claim(String eventsKey, String recordId) {
        List<Object> raw = exec("XCLAIM", eventsKey, GROUP, CONSUMER,
                String.valueOf(CLAIM_MIN_IDLE_MILLIS), recordId);
        if (raw.isEmpty() || !(raw.get(0) instanceof List<?> entry) || entry.size() < 2) {
            return Map.of();
        }
        return toFieldMap(entry.get(1));
    }

    /**
     * 执行一条 Redis 原生命令。
     *
     * <p>为什么这条链路不用 {@code opsForStream()} 的 claim API：死信判据要投递次数，
     * 而类型化 API 只给消息体。与其一半类型化一半原生，不如回收这段整体统一。
     * 读取新消息仍然走 {@code opsForStream()}——那边不需要投递次数，用类型化 API 更稳。
     */
    @SuppressWarnings("unchecked")
    private List<Object> exec(String command, String... args) {
        byte[][] argv = new byte[args.length][];
        for (int i = 0; i < args.length; i++) {
            argv[i] = args[i].getBytes(StandardCharsets.UTF_8);
        }
        List<Object> result = redisTemplate.execute((RedisCallback<List<Object>>) connection -> {
            Object raw = connection.execute(command, argv);
            return raw instanceof List ? (List<Object>) raw : List.of();
        });
        return result == null ? List.of() : result;
    }

    private static Map<String, String> toFieldMap(Object rawFields) {
        if (!(rawFields instanceof List<?> fields)) {
            return Map.of();
        }
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < fields.size(); i += 2) {
            map.put(str(fields.get(i)), str(fields.get(i + 1)));
        }
        return map;
    }

    private static Long parseLong(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.valueOf(str(raw));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long toLong(Object raw, long fallback) {
        Long value = parseLong(raw);
        return value == null ? fallback : value;
    }

    /** RESP 里的字符串是 byte[]；已经反序列化好的则原样 toString */
    private static String str(Object raw) {
        if (raw instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return String.valueOf(raw);
    }
}
