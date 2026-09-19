package com.hailin.blogsystem.security;

import com.hailin.blogsystem.constants.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 登录失败次数限制（只作用于账号密码登录，不碰 GitHub OAuth）。
 *
 * **两个独立维度，非等权**——因为不同的攻击形态只能被不同的维度看见：
 *
 * | 攻击形态 | 账号维度 | IP 维度 | 「用户名 + IP」组合 |
 * |---|---|---|---|
 * | 单 IP 定向爆破（一个账号硬撞） | ✅ | ✅ | ✅ |
 * | 分布式爆破（一个账号、代理池多 IP） | ✅ | ❌ | ❌ |
 * | 撞库（泄露库，多账号各试一两次、同源 IP） | ❌ | ✅ | ❌ |
 *
 * - **账号维度（严：5 次 / 15 分钟）**：分布式爆破时每个 IP 都只试几次，任何按 IP 或按
 *   「用户名 + IP」组合计数的方案都攒不到阈值——只有账号维度看得见。
 * - **IP 维度（宽：30 次 / 15 分钟）**：撞库时每个账号只失败一两次，账号维度攒不到阈值——
 *   只有 IP 维度看得出「这个来源在批量试」。
 *
 * 「用户名 + IP」组合键只覆盖第一种。它消掉了"账号能被锁死"这个副作用，代价是漏掉两种
 * 主流攻击——那不是漏拦一点点，是限流装置形同虚设。本类选择**保留覆盖面，
 * 用宽阈值 + 短窗口把误伤压到可接受**。
 *
 * **残留代价（已知，不假装解决）**：账号维度仍可被用来锁死真实用户——攻击者拿别人的
 * 用户名连试 5 次即可。代价被限制在「15 分钟不能登录」，且该用户登录成功即清零。
 * 彻底消除需要换成渐进式延迟 / 验证码（OWASP 对这一条的建议），不在本项目范围内。
 *
 * **成功登录只清账号键，不清 IP 键**：IP 计数表达的是"这个来源可疑"，张三登录成功
 * 证明不了同一 NAT 出口下的李四没在撞库；更关键的是，清 IP 键等于留后门——
 * 攻击者只要有一个自己的账号，就能靠反复登录成功把 IP 计数刷掉。
 *
 * **用户名必须归一化**（trim + 小写）。DB 的 collation 是 `utf8mb4_unicode_ci`，
 * `WHERE username='Admin'` 能命中 `admin`；不归一化的话 `Admin` / `ADMIN` / `admin`
 * 在 Redis 里是三个计数键，5 次阈值被拆成 15 次。归一化后与 DB 的比对语义一致，
 * 不存在误伤（DB 本来就不区分这两个名字）。
 * 边界：只处理大小写与首尾空格，不做完整 Unicode 折叠——残留的同形字变体只会让计数分散，
 * 不构成直接入侵。
 *
 * **失败计数与锁定共用同一个键**（固定窗口 15 分钟）：计到上限后窗口内一律拒绝，
 * 窗口自然过期即解锁，不需要第二个"锁定标记"键。
 *
 * **Redis 故障时 fail-open（放行）+ ERROR 日志**：拒绝会让 Redis 一抖就等于全站登不进去，
 * 而放行的风险有 bcrypt（每次约 100ms）天然限速兜底。这个取舍不做成配置开关——
 * 这里没有"两种都合理"的情形，留一个开关只是给人配错的机会。
 */
@Slf4j
@Component
public class LoginAttemptLimiter {

    /** 账号维度阈值：第 MAX_ACCOUNT_FAILURES + 1 次起拒绝 */
    private static final int MAX_ACCOUNT_FAILURES = 5;
    /** IP 维度阈值：宽得多——它只为拦住"单账号失败少、来源总量大"的撞库，正常 NAT 出口不该被它拦下 */
    private static final int MAX_IP_FAILURES = 30;
    /** 计数窗口（秒），同时也是锁定时长 */
    private static final long WINDOW_SECONDS = 900L;
    /** 用户名截断长度，对齐 `users.username` 的 varchar(50)，防止超长输入撑大 key */
    private static final int MAX_USERNAME_LENGTH = 50;

    /**
     * 记一次失败：INCR + 首次 EXPIRE 在一条脚本里完成。
     *
     * 分两次调用的话，INCR 成功而 EXPIRE 失败（或进程崩在中间）→ 计数键**永不过期** →
     * 用户被永久锁定，重启也不恢复。这是这个脚本存在的唯一理由。
     *
     * 脚本正文与 {@link AiRateLimiter} 里的那份字节相同，但**刻意不共享**：
     * 那边是"每次调用消费一个配额"，这边是"只在失败时计数、成功要清零"，
     * 契约不同、演化方向也不同。共享一个常量会制造"改这里会不会影响那边"的隐性耦合，
     * 而 4 行 Lua 重复的成本远低于一次错误抽象。
     * 触发条件：出现第三处，或两处开始同步演进时再抽公共组件。
     */
    private static final DefaultRedisScript<Long> RECORD_FAILURE_SCRIPT = new DefaultRedisScript<>(
            """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """,
            Long.class
    );

    private final StringRedisTemplate redisTemplate;

    public LoginAttemptLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 任一维度超限即抛 {@link RateLimitExceededException}（HTTP 429 + Retry-After），Redis 故障则放行。
     * 必须在查库**之前**调用：锁定时既不消耗 bcrypt，也不因"是否被锁"泄露账号是否存在。
     */
    public void checkBlocked(String username, String clientIp) {
        try {
            checkDimension(accountKey(username), MAX_ACCOUNT_FAILURES, "账号", username, clientIp);

            if (StringUtils.hasText(clientIp)) {
                checkDimension(RedisConstants.LOGIN_FAIL_IP_KEY_PREFIX + clientIp,
                        MAX_IP_FAILURES, "IP", username, clientIp);
            }
        } catch (RateLimitExceededException e) {
            throw e;  // 业务拒绝必须原样抛出，不能被下面的 Redis 故障兜底吞掉
        } catch (RuntimeException e) {
            log.error("[LOGIN-LIMIT] Redis 故障，fail-open 放行: username={}", username, e);
        }
    }

    /**
     * 记一次登录失败。**账号不存在时同样要调用**——
     * 否则"会不会被限流"就成了账号是否存在的探针，与"提示语泛化"的目标直接冲突。
     * 两个维度各自捕获异常：一个维度写失败不该拖掉另一个。
     */
    public void recordFailure(String username, String clientIp) {
        record(accountKey(username), MAX_ACCOUNT_FAILURES, "账号", username);

        if (StringUtils.hasText(clientIp)) {
            record(RedisConstants.LOGIN_FAIL_IP_KEY_PREFIX + clientIp, MAX_IP_FAILURES, "IP", username);
        }
    }

    /**
     * 登录成功后清零**账号维度**：计数器表达的是"这个账号连续失败"，成功即归零。
     * IP 维度不清——理由见类注释。
     */
    public void clear(String username) {
        try {
            redisTemplate.delete(accountKey(username));
        } catch (RuntimeException e) {
            log.error("[LOGIN-LIMIT] 清零失败计数出错: username={}", username, e);
        }
    }

    private void checkDimension(String key, int maxFailures, String dimension, String username, String clientIp) {
        String value = redisTemplate.opsForValue().get(key);
        int failures = value == null ? 0 : Integer.parseInt(value);
        if (failures < maxFailures) {
            return;
        }

        // 超限是罕见路径，多查一次 TTL 换一个准确的 Retry-After，值
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        long retryAfter = (ttl == null || ttl <= 0) ? WINDOW_SECONDS : ttl;

        log.warn("[LOGIN-LIMIT] 拒绝登录 维度={} username={} ip={} failures={} retryAfter={}s",
                dimension, username, clientIp, failures, retryAfter);
        throw new RateLimitExceededException(retryAfter, "登录失败次数过多，请稍后再试");
    }

    private void record(String key, int maxFailures, String dimension, String username) {
        try {
            Long count = redisTemplate.execute(
                    RECORD_FAILURE_SCRIPT,
                    List.of(key),
                    String.valueOf(WINDOW_SECONDS)
            );

            if (count != null && count >= maxFailures) {
                log.warn("[LOGIN-LIMIT] {} 维度已达失败上限，后续登录将被拒绝: username={} count={}",
                        dimension, username, count);
            }
        } catch (RuntimeException e) {
            // 计数失败不影响本次响应：本次仍是"用户名或密码错误"
            log.error("[LOGIN-LIMIT] 记录失败次数出错: 维度={} username={}", dimension, username, e);
        }
    }

    private String accountKey(String username) {
        return RedisConstants.LOGIN_FAIL_ACCOUNT_KEY_PREFIX + normalize(username);
    }

    /** trim + 小写，与 DB 的 `utf8mb4_unicode_ci` 比对语义对齐；Locale.ROOT 避免土耳其语 i 问题 */
    private String normalize(String username) {
        String normalized = username.trim().toLowerCase(Locale.ROOT);
        return normalized.length() > MAX_USERNAME_LENGTH
                ? normalized.substring(0, MAX_USERNAME_LENGTH)
                : normalized;
    }
}
