package com.hailin.blogsystem.controller;

import com.hailin.blogsystem.entity.vo.ActivityStatsVO;
import com.hailin.blogsystem.service.ActivityStatsService;
import com.hailin.blogsystem.utils.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final ActivityStatsService activityStatsService;

    /**
     * 站点活跃统计。
     *
     * **公开接口**：返回的只有计数，不含任何用户信息；首页与个人中心都可能展示，
     * 因此不挂登录拦截器（未匹配拦截器规则的路径本来就放行）。
     */
    @GetMapping("/activity")
    public Result<ActivityStatsVO> getActivityStats() {
        return Result.success(activityStatsService.getStats());
    }
}
