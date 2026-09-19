package com.hailin.blogsystem.component;

import com.hailin.blogsystem.config.BlogSeckillProperties;
import com.hailin.blogsystem.service.SeckillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动完成后把 ACTIVE 活动预热到 Redis。
 *
 * <p>为什么是「启动预热 + 手动重建」而不是抢购路径上的懒预热：懒预热要加锁防并发，
 * 而且预热失败的那段时间用户拿到的是「活动暂未开放」——一个查不出原因的错误。
 * 抢购是热路径，不该在里面做重建。
 *
 * <p><b>预热失败不阻断应用启动</b>：秒杀只是站内一个功能，Redis 暂时不可用
 * 不该让整个博客起不来。失败时留一条 error 日志，修好后用运维接口重建即可
 * （{@code POST /api/seckill/admin/preheat}）。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SeckillPreheatRunner implements ApplicationRunner {

    private final SeckillService seckillService;
    private final BlogSeckillProperties blogSeckillProperties;

    @Override
    public void run(ApplicationArguments args) {
        if (!blogSeckillProperties.isPreheatOnStartup()) {
            log.info("[SECKILL] 启动预热已关闭（blog.seckill.preheat-on-startup=false）");
            return;
        }
        try {
            int count = seckillService.preheat(null);
            log.info("[SECKILL] 启动预热完成，共 {} 个 ACTIVE 活动", count);
        } catch (RuntimeException e) {
            log.error("[SECKILL] 启动预热失败。秒杀在预热成功前不可用（其他功能不受影响），"
                    + "可用 POST /api/seckill/admin/preheat 重建", e);
        }
    }
}
