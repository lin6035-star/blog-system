package com.hailin.blogsystem.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hailin.blogsystem.ai.billing.BillingHandle;
import com.hailin.blogsystem.entity.AiBillingOrder;
import com.hailin.blogsystem.mapper.AiBillingOrderMapper;
import com.hailin.blogsystem.service.AiBillingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 超时预扣的兜底释放。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §5.3 / §7.4
 *
 * <p><b>这是进程崩溃时的兜底</b>——正常路径永远走不到它：
 * 成功走 {@code settle}，失败 / 取消走 {@code release}，只有进程在中途没了
 * 才会留下一条永远停在 RESERVED 的单据。
 *
 * <p><b>为什么这段逻辑在 Task 而不是 ServiceImpl 里</b>：
 * {@code AiBillingService.release} 靠 {@code @Transactional} 保证
 * 「推进状态 + 退款 + 写流水」原子。如果在本类里自调用（this.release），
 * 不走代理就没有事务——退款和状态推进会被拆成两次独立提交，
 * 中间失败会留下「退了钱但单据还是 RESERVED」的账，下一轮还会再退一次。
 *
 * <p>⚠️ 调度池 {@code spring.task.scheduling.pool.size: 1} 是<b>串行</b>的，
 * 所以这里限制单轮条数，避免一次积压把其他定时任务（浏览量同步 / 热度榜）挡住。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AiBillingExpireTask {

    /** 单轮上限：超时的会留到下一轮，不做一次性全量处理 */
    private static final int BATCH_SIZE = 100;

    private static final String STATUS_RESERVED = "RESERVED";

    private final AiBillingOrderMapper aiBillingOrderMapper;
    private final AiBillingService aiBillingService;

    @Scheduled(fixedDelay = 5 * 60 * 1000L)
    public void releaseExpiredReserves() {
        List<AiBillingOrder> expired = aiBillingOrderMapper.selectList(
                new LambdaQueryWrapper<AiBillingOrder>()
                        .eq(AiBillingOrder::getStatus, STATUS_RESERVED)
                        .lt(AiBillingOrder::getExpireAt, LocalDateTime.now())
                        .last("LIMIT " + BATCH_SIZE));

        if (expired.isEmpty()) {
            return;
        }

        log.warn("[BILLING] 发现 {} 笔超时未结算的预扣，开始兜底释放（正常路径不该出现）", expired.size());
        for (AiBillingOrder order : expired) {
            try {
                aiBillingService.release(new BillingHandle(
                        order.getOrderNo(), order.getUserId(), order.getReservedCredit()));
            } catch (Exception e) {
                // 单条失败不影响其余；它仍停在 RESERVED，下一轮会再被捞到
                log.warn("[BILLING] 超时释放失败：order_no={}", order.getOrderNo(), e);
            }
        }
    }
}
