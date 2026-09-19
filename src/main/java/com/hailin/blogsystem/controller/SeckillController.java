package com.hailin.blogsystem.controller;

import com.hailin.blogsystem.config.BlogSeckillProperties;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.vo.SeckillActivityVO;
import com.hailin.blogsystem.entity.vo.SeckillResultVO;
import com.hailin.blogsystem.exception.BusinessException;
import com.hailin.blogsystem.service.SeckillService;
import com.hailin.blogsystem.utils.Result;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 秒杀接口。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §6
 *
 * <p>活动列表公开（游客能看还剩多少），抢购与查结果需要登录。
 */
@RestController
@RequestMapping("/api/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillService seckillService;
    private final BlogSeckillProperties blogSeckillProperties;

    /** 活动列表。游客可为 null：那样不查任何用户状态，{@code myStatus} 一律 NONE */
    @GetMapping("/activities")
    public Result<List<SeckillActivityVO>> listActivities() {
        return Result.success(seckillService.listActivities(UserContext.get()));
    }

    /**
     * 抢购。
     *
     * <p><b>返回的 {@code QUEUED} 是「排队中」不是「抢到了」</b>——预占成功只代表名额占住了，
     * 真正的入账由消费者异步完成。前端必须轮询 {@code /result} 拿确定性结论，
     * 否则「说好抢到了、额度却没到」就无解了。
     */
    @PostMapping("/activities/{activityId}/grab")
    public Result<SeckillResultVO> grab(@PathVariable Long activityId) {
        return Result.success(seckillService.grab(activityId, currentUserId()));
    }

    /** 抢购结果（前端轮询用，1 秒一次、最多 5 次足够——结果通常在百毫秒内产出） */
    @GetMapping("/activities/{activityId}/result")
    public Result<SeckillResultVO> getResult(@PathVariable Long activityId) {
        return Result.success(seckillService.getResult(activityId, currentUserId()));
    }

    /**
     * 预热 / 重建 Redis 状态（运维接口）。{@code activityId} 为空则重建全部 ACTIVE 活动。
     *
     * <p>项目还没有角色体系，用 {@code blog.seckill.admin-user-ids} 白名单做最小权限，
     * <b>默认空 = 谁都不能调</b>（fail-safe：漏配的后果是没人能用，而不是谁都能用）。
     * 升级路径是给 users 加 role + 统一鉴权，那时删掉这个配置项。
     */
    @PostMapping("/admin/preheat")
    public Result<Integer> preheat(@RequestParam(required = false) Long activityId) {
        Long userId = currentUserId();
        if (!blogSeckillProperties.getAdminUserIds().contains(userId)) {
            // 提示语不区分「没登录」和「不在名单里」，也不回显白名单——避免成为账号枚举的探针
            throw new BusinessException(BlogConstants.ErrorCode.FORBIDDEN, "无权执行该操作");
        }
        return Result.success(seckillService.preheat(activityId));
    }

    private Long currentUserId() {
        Long userId = UserContext.get();
        if (userId == null) {
            throw new IllegalStateException("未登录上下文：/api/seckill/** 应由 JwtInterceptor 拦截");
        }
        return userId;
    }
}
