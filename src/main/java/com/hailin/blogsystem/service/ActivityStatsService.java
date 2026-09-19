package com.hailin.blogsystem.service;

import com.hailin.blogsystem.component.UserActivityTracker;
import com.hailin.blogsystem.entity.vo.ActivityStatsVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 站点活跃统计（基于 {@link UserActivityTracker} 的位图）。
 *
 * **为什么不加缓存**：这里的开销 = 一次 BITCOUNT（微秒级）+ 一次 7 天的 BITOP。
 * 位图大小只取决于**最大 userId**，当前规模下每天一个 key 也就几百字节——
 * 比一次普通的缓存读还轻。**触发条件**：userId 到百万级（单 key 上百 KB），
 * 或者这个接口被高频轮询时，再加短 TTL 缓存。
 */
@Service
@RequiredArgsConstructor
public class ActivityStatsService {

    /** 周活跃窗口：含今天共 7 天 */
    private static final int WEEK_WINDOW_DAYS = 7;

    private final UserActivityTracker userActivityTracker;

    public ActivityStatsVO getStats() {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);

        long todayActive = userActivityTracker.countActive(today);
        long yesterdayActive = userActivityTracker.countActive(yesterday);
        long weekActive = userActivityTracker.countActiveBetween(
                today.minusDays(WEEK_WINDOW_DAYS - 1L), today);
        long retained = userActivityTracker.countRetained(yesterday, today);

        return new ActivityStatsVO(
                todayActive,
                yesterdayActive,
                weekActive,
                retained,
                // 分母为 0 时返回 null：留存率"无法计算"不等于"0%"
                yesterdayActive == 0 ? null : (double) retained / yesterdayActive
        );
    }
}
