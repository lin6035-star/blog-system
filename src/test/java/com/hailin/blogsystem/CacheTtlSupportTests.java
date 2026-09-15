package com.hailin.blogsystem;

import com.hailin.blogsystem.component.CacheTtlSupport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缓存 TTL 抖动（防雪崩）。
 *
 * 抖动是随机的，因此断言的是**区间与边界**，不是精确值。
 */
class CacheTtlSupportTests {

    /** 与实现保持一致：±20% */
    private static final double JITTER_RATIO = 0.2;

    private final CacheTtlSupport support = new CacheTtlSupport();

    @Test
    void jitterStaysWithinTwentyPercentOfBase() {
        Duration base = Duration.ofMinutes(10);
        long baseMs = base.toMillis();
        long maxOffset = (long) (baseMs * JITTER_RATIO);

        for (int i = 0; i < 1000; i++) {
            long actual = support.jitter(base).toMillis();
            assertThat(actual)
                    .as("抖动后的 TTL 必须落在 ±20% 区间内")
                    .isBetween(baseMs - maxOffset, baseMs + maxOffset);
        }
    }

    @Test
    void jitterActuallySpreadsValues() {
        Duration base = Duration.ofMinutes(10);
        Set<Long> distinct = new HashSet<>();

        for (int i = 0; i < 200; i++) {
            distinct.add(support.jitter(base).toMillis());
        }

        // 若退化成固定值，同一批 key 仍会同时失效——雪崩防护就失效了
        assertThat(distinct).hasSizeGreaterThan(50);
    }

    @Test
    void nullAndNonPositiveInputReturnedAsIs() {
        assertThat(support.jitter(null)).isNull();
        assertThat(support.jitter(Duration.ZERO)).isEqualTo(Duration.ZERO);
        assertThat(support.jitter(Duration.ofMinutes(-5))).isEqualTo(Duration.ofMinutes(-5));
    }

    @Test
    void tinyTtlNeverBecomesNonPositive() {
        // 1ms 的基础值算出的偏移量为 0，实现会原样返回；无论如何不能产出 <= 0 的 TTL
        Duration tiny = Duration.ofMillis(1);

        for (int i = 0; i < 500; i++) {
            assertThat(support.jitter(tiny).toMillis()).isPositive();
        }
    }

    @Test
    void shortestSecondsScaleTtlStaysPositive() {
        Duration base = Duration.ofSeconds(1);

        for (int i = 0; i < 500; i++) {
            assertThat(support.jitter(base).toMillis()).isPositive();
        }
    }
}
