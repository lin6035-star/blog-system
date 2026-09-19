package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hailin.blogsystem.component.SeckillPreheatedEvent;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.constants.SeckillKeys;
import com.hailin.blogsystem.entity.SeckillActivity;
import com.hailin.blogsystem.entity.SeckillOrder;
import com.hailin.blogsystem.entity.vo.SeckillActivityVO;
import com.hailin.blogsystem.entity.vo.SeckillResultVO;
import com.hailin.blogsystem.exception.BusinessException;
import com.hailin.blogsystem.mapper.SeckillActivityMapper;
import com.hailin.blogsystem.mapper.SeckillOrderMapper;
import com.hailin.blogsystem.service.SeckillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀实现：Redis 预占 + DB 最终裁决。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §6
 *
 * <p><b>为什么抢购必须是一个 Lua</b>：{@code GET 判断库存} 和 {@code DECR 扣库存} 两步
 * 分开执行，中间能被别的请求插队——两个人都读到「还剩 1」，然后都扣成功，直接超卖。
 * 脚本里还顺带做了查重和发事件，所以「占名额」和「留下入账凭据」也是原子的。
 *
 * <p><b>为什么 XADD 也要在脚本里</b>：如果 Lua 只扣库存、事件由 Java 侧补投，
 * 那么「扣成功但进程崩溃 / 投递失败」= 名额凭空消失，用户永远停在「排队中」。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SeckillServiceImpl implements SeckillService {

    private final StringRedisTemplate redisTemplate;
    private final SeckillActivityMapper seckillActivityMapper;
    private final SeckillOrderMapper seckillOrderMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 抢购脚本（设计稿 §6.2）。
     *
     * <p><b>返回值下标约定</b>：{@code [0] = code}（永远是整数），{@code [1] = 附加信息}
     * （成功时是 eventId，失败时是原因字符串）。Redis 的 Lua number 走 RESP Integer，
     * 在 Spring Data Redis 里出来是 {@code Long}；字符串元素出来是 {@code String}
     * ——非 byte[] 的元素会被原样保留，所以这里不需要自己反序列化。
     *
     * <p>四个 KEYS 的顺序与 {@link SeckillKeys} 的 getter 严格对应：stock / users / meta / events。
     */
    private static final DefaultRedisScript<List> GRAB_SCRIPT = new DefaultRedisScript<>(
            """
            -- KEYS[1]=stock  KEYS[2]=users  KEYS[3]=meta  KEYS[4]=events
            -- ARGV[1]=userId  ARGV[2]=activityId  ARGV[3]=events 的兜底 TTL（秒）
            local stockType = redis.call('TYPE', KEYS[1])['ok']
            local usersType = redis.call('TYPE', KEYS[2])['ok']
            local metaType  = redis.call('TYPE', KEYS[3])['ok']
            local eventType = redis.call('TYPE', KEYS[4])['ok']
            if stockType ~= 'string' then return {-3, 'BAD_STOCK_KEY'} end
            if usersType ~= 'none' and usersType ~= 'set' then return {-3, 'BAD_USERS_KEY'} end
            if metaType ~= 'hash' then return {-3, 'BAD_META_KEY'} end
            if eventType ~= 'none' and eventType ~= 'stream' then return {-3, 'BAD_STREAM_KEY'} end

            local status = redis.call('HGET', KEYS[3], 'status')
            local startAt = tonumber(redis.call('HGET', KEYS[3], 'startAt') or '0')
            local endAt   = tonumber(redis.call('HGET', KEYS[3], 'endAt') or '0')
            -- TIME 返回 {秒, 微秒}，元素是**字符串**，必须 tonumber 才能比较，否则 Lua 报
            -- "attempt to compare string with number" ——脚本抛错 = 抢购全挂
            local now = tonumber(redis.call('TIME')[1])
            if status ~= 'ACTIVE' or now < startAt or now >= endAt then return {-2, 'NOT_ACTIVE'} end

            if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return -1 end   -- 已抢过
            local stock = tonumber(redis.call('GET', KEYS[1]) or '-1')
            if stock <= 0 then return 0 end                                        -- 已抢完

            -- 先生成可靠事件，再更新快速路径状态。Lua 运行时错误不会回滚，
            -- 类型预检先排除最常见的 WRONGTYPE，event-first 避免「扣了资格却没有事件」。
            -- 反过来的残留（有事件、名单没记上）是可恢复的：消费者仍由 DB 库存和唯一索引裁决，
            -- 对账负责发现三个视图不一致。
            local eventId = redis.call('XADD', KEYS[4], '*',
                                       'activityId', ARGV[2], 'userId', ARGV[1])
            redis.call('DECR', KEYS[1])
            redis.call('SADD', KEYS[2], ARGV[1])
            -- users 和 events 都是**抢购时才创建**的，创建出来的 key 不带 TTL。
            -- 而 volatile-lru **只淘汰设了 TTL 的 key**，没 TTL 的会一直占着内存，
            -- 内存满了走 OOM 而不是淘汰——所以在这里补上。
            -- 预热时建的那三个（stock/meta/users 回填）已经在 preheatOne 里设过，
            -- 这里判 TTL < 0 只是兜底；**不能无条件 EXPIRE**，否则每次抢购都把
            -- 过期时间往后推，等于永不过期
            if redis.call('TTL', KEYS[2]) < 0 then
                redis.call('EXPIRE', KEYS[2], ARGV[3])
            end
            if redis.call('TTL', KEYS[4]) < 0 then
                redis.call('EXPIRE', KEYS[4], ARGV[3])
            end
            return {1, eventId}
            """,
            List.class);

    /** 脚本返回值下标：code 永远在 0，附加信息在 1 */
    private static final int IDX_CODE = 0;
    private static final int IDX_EXTRA = 1;

    /* 脚本返回码 */
    private static final int CODE_OK = 1;
    private static final int CODE_SOLD_OUT = 0;
    private static final int CODE_DUPLICATE = -1;
    private static final int CODE_NOT_ACTIVE = -2;
    private static final int CODE_BAD_KEY = -3;

    /**
     * 活动结束后，{@code stock} / {@code users} / {@code meta} 再保留多久。
     *
     * <p>活动一结束这三个 key 就没有读者了（结果查询和一人一单都以 DB 为准），
     * 留一周纯属给对账任务和排查留窗口。
     */
    private static final long STATE_RETENTION_AFTER_END_SECONDS = Duration.ofDays(7).toSeconds();

    /**
     * {@code users} / {@code events} 的兜底 TTL——这两个 key 都是**抢购时才创建**的，
     * 所以只能由抢购脚本补 TTL，且从它**被创建**的那一刻算起。
     *
     * <p>这里给固定时长而不是「到活动结束」：抢购脚本不打 DB、拿不到 {@code endAt}，
     * 而为算 TTL 去查一次库等于把「抢购不打 DB」这条设计破了（那条链路的全部性能优势都在这）。
     * 30 天远超任何合理的处理延迟——真正卡住的事件会在 5 分钟内走完 5 次重试进死信。
     */
    private static final long LAZY_KEY_TTL_SECONDS = Duration.ofDays(30).toSeconds();

    @Override
    public List<SeckillActivityVO> listActivities(Long userId) {
        List<SeckillActivity> activities = seckillActivityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivity>()
                        .orderByDesc(SeckillActivity::getStartAt));

        return activities.stream().map(activity -> {
            SeckillActivityVO vo = new SeckillActivityVO();
            vo.setId(activity.getId());
            vo.setName(activity.getName());
            vo.setTotalStock(activity.getTotalStock());
            vo.setSoldCount(activity.getSoldCount());
            // 展示走 DB 口径：Redis 那个数会因为预热窗口、在途事件而短暂偏离，
            // 「还剩 N 份」应当以真正发出去的为准
            vo.setRemainingStock(Math.max(0, activity.getTotalStock() - activity.getSoldCount()));
            vo.setCreditAmount(activity.getCreditAmount());
            vo.setStartAt(activity.getStartAt());
            vo.setEndAt(activity.getEndAt());
            vo.setStatus(activity.getStatus());
            vo.setMyStatus(userId == null
                    ? SeckillResultVO.NONE
                    : getResult(activity.getId(), userId).getStatus());
            return vo;
        }).toList();
    }

    @Override
    public SeckillResultVO grab(Long activityId, Long userId) {
        List<?> raw;
        try {
            raw = redisTemplate.execute(GRAB_SCRIPT,
                    List.of(SeckillKeys.stock(activityId),
                            SeckillKeys.users(activityId),
                            SeckillKeys.meta(activityId),
                            SeckillKeys.events(activityId)),
                    String.valueOf(userId), String.valueOf(activityId),
                    String.valueOf(LAZY_KEY_TTL_SECONDS));
        } catch (RuntimeException e) {
            // **fail-closed，不能 fail-open**：整条链路依赖「预占 + 事件」都在 Redis 里完成，
            // Redis 没了就没有事件可发，消费者也不会入账——放行到 DB 只会让用户看到
            // 「排队中」然后永远等下去。宁可当场说「稍后重试」。
            log.error("[SECKILL] 抢购脚本执行失败 activityId={} userId={}", activityId, userId, e);
            throw new BusinessException(BlogConstants.ErrorCode.SERVER_ERROR, "抢购服务繁忙，请稍后重试");
        }

        if (raw == null || raw.isEmpty() || !(raw.get(IDX_CODE) instanceof Number code)) {
            log.error("[SECKILL] 抢购脚本返回异常 activityId={} userId={} raw={}", activityId, userId, raw);
            throw new BusinessException(BlogConstants.ErrorCode.SERVER_ERROR, "抢购失败，请稍后重试");
        }

        return switch (code.intValue()) {
            // 注意语义：这是「预占成功」，不是「已入账」——UI 上必须显示排队中（设计稿 §6.3）
            case CODE_OK -> SeckillResultVO.of(SeckillResultVO.QUEUED, "已为你占住名额，正在入账");
            case CODE_SOLD_OUT -> SeckillResultVO.of(SeckillResultVO.SOLD_OUT, "手慢了，已经抢完");
            case CODE_DUPLICATE -> SeckillResultVO.of(SeckillResultVO.DUPLICATE, "你已经抢过这一场了");
            case CODE_NOT_ACTIVE -> SeckillResultVO.of(SeckillResultVO.NOT_ACTIVE, "活动未开始或已结束");
            // 类型预检失败 = 没预热，或 key 被别的东西占了。这是**运维问题不是用户问题**，
            // 表现却只是「所有人都抢不到」——不留可检索日志就完全无从排查
            case CODE_BAD_KEY -> {
                String reason = raw.size() > IDX_EXTRA ? String.valueOf(raw.get(IDX_EXTRA)) : "UNKNOWN";
                log.error("[SECKILL-KEY-BROKEN] 活动 {} 的 Redis key 类型不符（{}），疑似未预热或被占用",
                        activityId, reason);
                yield SeckillResultVO.of(SeckillResultVO.NOT_ACTIVE, "活动暂未开放");
            }
            default -> {
                log.error("[SECKILL] 抢购脚本返回未知码 {} activityId={} userId={}",
                        code, activityId, userId);
                yield SeckillResultVO.of(SeckillResultVO.NOT_ACTIVE, "活动暂未开放");
            }
        };
    }

    @Override
    public SeckillResultVO getResult(Long activityId, Long userId) {
        SeckillOrder order = seckillOrderMapper.selectByUserAndActivity(userId, activityId);
        if (order != null) {
            return switch (order.getStatus()) {
                case BlogConstants.Seckill.ORDER_GRANTED -> SeckillResultVO.granted(order.getCreditAmount());
                // 业务失败（DB 说售罄）：终态。重试不会有不同结果，所以不置「重试中」误导用户
                case BlogConstants.Seckill.ORDER_FAILED ->
                        SeckillResultVO.of(SeckillResultVO.FAILED, "很遗憾，名额已经发完了");
                default ->
                        SeckillResultVO.of(SeckillResultVO.FAILED_RETRY, "发放遇到问题，我们正在处理");
            };
        }

        // DB 里还没有订单：要么事件还压在队列里，要么根本没参与过。
        // 用 Redis 的预占名单区分这两者——这是它除了查重之外的第二个用途。
        Boolean queued;
        try {
            queued = redisTemplate.opsForSet()
                    .isMember(SeckillKeys.users(activityId), String.valueOf(userId));
        } catch (RuntimeException e) {
            // 轮询接口，不能因为 Redis 抖动就把 500 刷给前端。
            // 返回 NONE 会让按钮变回「立即抢购」，但点了也是 fail-closed 拒绝，不会多发
            log.warn("[SECKILL] 查询预占状态失败 activityId={} userId={}", activityId, userId, e);
            return SeckillResultVO.of(SeckillResultVO.NONE, null);
        }

        return Boolean.TRUE.equals(queued)
                ? SeckillResultVO.of(SeckillResultVO.QUEUED, "已为你占住名额，正在入账")
                : SeckillResultVO.of(SeckillResultVO.NONE, null);
    }

    @Override
    public int preheat(Long activityId) {
        List<SeckillActivity> activities = activityId == null
                ? seckillActivityMapper.selectList(new LambdaQueryWrapper<SeckillActivity>()
                        .eq(SeckillActivity::getStatus, BlogConstants.Seckill.ACTIVITY_ACTIVE))
                : Optional.ofNullable(seckillActivityMapper.selectById(activityId)).stream().toList();

        activities.forEach(this::preheatOne);
        // 通知消费者清掉活动列表缓存。没有这一步的话，新预热的活动最长要等 60 秒
        // 才会被消费——用户看到的就是「抢到了但一直排队中」（见 SeckillPreheatedEvent）
        eventPublisher.publishEvent(new SeckillPreheatedEvent());
        return activities.size();
    }

    /**
     * 重建单个活动的 Redis 状态。
     *
     * <p><b>刻意只清三个状态 key、保留 events</b>：Stream 是「已预占、还没入账」的事件队列，
     * 和 stock / users / meta 不是一回事。把它一起删掉 = 那些名额凭空消失，
     * 用户永远停在「排队中」，而库存已经少了一份。
     *
     * <p><b>users 只回填 DB 里 GRANTED 的人，不回填 Stream 里在途的</b>。代价是预热那一刻
     * 在途的用户可以再点一次抢购——但不会超发：第二次走到 DB 会被 {@code uk_user_activity}
     * 挡住（消费者查到已有订单直接 ACK），而 Redis 侧多扣的那一个名额由
     * {@code sold_count < total_stock} 最终裁决兜住。净效果是 Redis **可能少发，绝不会多发**。
     * 为这点窗口去 XRANGE 全量扫 Stream 不值得，预热是低频维护路径不是热路径。
     */
    private void preheatOne(SeckillActivity activity) {
        Long id = activity.getId();

        redisTemplate.delete(List.of(SeckillKeys.stock(id),
                SeckillKeys.users(id),
                SeckillKeys.meta(id)));

        // ⚠️ 灌的是「总库存 − DB 已发放」，不是总库存。取错口径等于每重建一次就多发一批
        int remaining = Math.max(0, activity.getTotalStock() - activity.getSoldCount());
        redisTemplate.opsForValue().set(SeckillKeys.stock(id), String.valueOf(remaining));

        List<SeckillOrder> granted = seckillOrderMapper.selectList(
                new LambdaQueryWrapper<SeckillOrder>()
                        .eq(SeckillOrder::getActivityId, id)
                        .eq(SeckillOrder::getStatus, BlogConstants.Seckill.ORDER_GRANTED));
        if (!granted.isEmpty()) {
            redisTemplate.opsForSet().add(SeckillKeys.users(id),
                    granted.stream().map(o -> String.valueOf(o.getUserId())).toArray(String[]::new));
        }

        Map<String, String> meta = new HashMap<>();
        meta.put("status", activity.getStatus());
        // 时间统一用**秒**，因为 Lua 里拿的是 redis.call('TIME')[1]（Unix 时间戳）。
        // 单位对不上不会报错，只会静默地把活动判成「未开始」——最难查的那种坏法
        meta.put("startAt", String.valueOf(toEpochSecond(activity.getStartAt())));
        meta.put("endAt", String.valueOf(toEpochSecond(activity.getEndAt())));
        // 对账基准：抢购脚本里 SADD 和 DECR 是同一次原子操作，所以「名单增量」必然
        // 等于「库存减量」。存下这两个基准，对账才有参照物——否则预热时就存在的
        // soldCount 会被误判成「Redis 三个视图不一致」
        meta.put("reservedBase", String.valueOf(granted.size()));
        meta.put("stockBase", String.valueOf(remaining));
        redisTemplate.opsForHash().putAll(SeckillKeys.meta(id), meta);

        // ⚠️ 这三个 key 必须带 TTL。部署模板用的是 maxmemory-policy: volatile-lru，
        // 而 volatile-lru **只淘汰设了 TTL 的 key**——没 TTL 的它碰都不碰，
        // 内存满了走 OOM 而不是淘汰。热度榜曾经是唯一无 TTL 的 key，补上之后
        // 秒杀这套顶替了它的位置，实测确认过（见 redis-ops-runbook.md §7.4）
        long stateTtl = ttlUntil(activity.getEndAt(), STATE_RETENTION_AFTER_END_SECONDS);
        redisTemplate.expire(SeckillKeys.stock(id), stateTtl, TimeUnit.SECONDS);
        redisTemplate.expire(SeckillKeys.users(id), stateTtl, TimeUnit.SECONDS);
        redisTemplate.expire(SeckillKeys.meta(id), stateTtl, TimeUnit.SECONDS);
        // events **不在这里创建**（它由抢购脚本的 XADD 创建），但**已经存在的要补 TTL**：
        // 抢购脚本只在新用户来抢时才跑，管不到存量 key——没人抢的活动就永远等不到那一次补救。
        // 判 == -1（存在但无 TTL）而不是无条件 EXPIRE，免得每次预热都把过期时间往后推
        String eventsKey = SeckillKeys.events(id);
        if (redisTemplate.getExpire(eventsKey, TimeUnit.SECONDS) == -1L) {
            redisTemplate.expire(eventsKey, LAZY_KEY_TTL_SECONDS, TimeUnit.SECONDS);
        }

        log.info("[SECKILL] 预热活动 {}（{}）：剩余 {} / 总 {}，已入账 {} 人，TTL {} 秒",
                id, activity.getName(), remaining, activity.getTotalStock(), granted.size(), stateTtl);
    }

    /**
     * 「活动结束后再保留 {@code bufferSeconds}」对应的 TTL。
     *
     * <p>⚠️ 活动已经结束时，剩余时间是负数，而 <b>{@code EXPIRE} 收到负数会立即删除 key</b>
     * ——预热一个刚过期的活动就把状态全清了，对账任务下一轮就会看到「Redis 预占数少于 DB 订单数」。
     * 所以下限是 buffer 本身，不随剩余时间下沉。
     */
    private static long ttlUntil(LocalDateTime endAt, long bufferSeconds) {
        long remaining = Duration.between(LocalDateTime.now(), endAt).getSeconds();
        return Math.max(bufferSeconds, remaining + bufferSeconds);
    }

    /**
     * {@code LocalDateTime} → Unix 秒。
     *
     * <p>必须用 JVM 默认时区：库里的 {@code LocalDateTime} 是按它写入的，
     * 而 Redis 的 TIME 是绝对时间戳。写死 +08:00 在 UTC 服务器上会让活动时间整体偏 8 小时。
     */
    private static long toEpochSecond(LocalDateTime dateTime) {
        return dateTime.atZone(ZoneId.systemDefault()).toEpochSecond();
    }
}
