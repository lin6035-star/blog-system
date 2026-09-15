package com.hailin.blogsystem.component;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 缓存 TTL 抖动（防雪崩）。
 *
 * **解决什么**：缓存 TTL 全是固定值，批量写入的 key 会在同一时刻集体失效——
 * 那一瞬间所有请求同时穿透到 DB。典型触发：服务重启后第一批请求、爬虫批量扫文章。
 * 给 TTL 加一个随机偏移，就把同一批 key 在时间轴上摊开了。
 *
 * **抖动比例 20%**：
 * - 太小（±1%）打不散——同批 key 仍然几乎同时失效
 * - 太大（±100%）让 TTL 语义失真——"10 分钟的缓存"可能 20 分钟才过期
 * - 20% 足以摊开，且 TTL 量级不变
 *
 * **只改 TTL 的算法，不改缓存语义**：命中率、失效规则、fail-open 一律不动。
 *
 * 边界：不做多级缓存、不做缓存预热（触发条件见
 * docs/redis/cache-breakdown-avalanche-design.md §五）。
 */
@Component
public class CacheTtlSupport {

    /** 抖动比例：±20% */
    private static final double JITTER_RATIO = 0.2;

    /**
     * 给基础 TTL 加 ±20% 随机抖动。
     * 空值 / 零值 / 负值原样返回（不制造无意义的 TTL）。
     */
    public Duration jitter(Duration base) {
        if (base == null || base.isZero() || base.isNegative()) {
            return base;
        }

        long baseMs = base.toMillis();
        long jitterMs = (long) (baseMs * JITTER_RATIO);
        if (jitterMs <= 0) {
            return base;
        }

        // 闭区间 [-jitterMs, +jitterMs]
        long offset = ThreadLocalRandom.current().nextLong(-jitterMs, jitterMs + 1);
        return Duration.ofMillis(Math.max(1L, baseMs + offset));
    }
}
