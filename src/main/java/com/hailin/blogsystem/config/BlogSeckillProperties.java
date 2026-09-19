package com.hailin.blogsystem.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 秒杀配置。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §6
 */
@Data
@ConfigurationProperties(prefix = "blog.seckill")
public class BlogSeckillProperties {

    /**
     * 可以调用预热接口的 userId 白名单。
     *
     * <p><b>默认空 = 谁都不能调</b>（fail-safe）。项目目前没有角色体系
     * （{@code users} 表没有 role 字段），这是最小权限的临时方案
     * ——写死在配置里而不是代码里，至少换人/换环境不用改代码。
     *
     * <p>升级路径：给 users 加 role 字段 + 统一鉴权拦截器，那时删掉这个配置项。
     */
    private List<Long> adminUserIds = new ArrayList<>();

    /**
     * 启动完成后自动预热全部 ACTIVE 活动。
     *
     * <p>测试里关掉：它会在上下文启动阶段就去连 Redis 写 key。
     */
    private boolean preheatOnStartup = true;
}
