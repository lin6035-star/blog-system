package com.hailin.blogsystem;

import com.hailin.blogsystem.security.LoginAttemptLimiter;
import com.hailin.blogsystem.security.RateLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginAttemptLimiterTests {

    private static final String ACCOUNT_KEY = "login:fail:account:admin";
    private static final String IP_KEY = "login:fail:ip:1.2.3.4";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private LoginAttemptLimiter limiter;

    @BeforeEach
    void setUp() {
        // lenient：不是每个用例都读计数（如 recordFailure / clear 的用例）
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        limiter = new LoginAttemptLimiter(redisTemplate);
    }

    @Test
    void belowThresholdIsAllowed() {
        when(valueOperations.get(anyString())).thenReturn("4");

        assertThatCode(() -> limiter.checkBlocked("admin", "1.2.3.4"))
                .doesNotThrowAnyException();
    }

    @Test
    void missingCounterIsAllowed() {
        when(valueOperations.get(anyString())).thenReturn(null);

        assertThatCode(() -> limiter.checkBlocked("admin", "1.2.3.4"))
                .doesNotThrowAnyException();
    }

    @Test
    void atThresholdIsRejectedWithRemainingWindow() {
        when(valueOperations.get(anyString())).thenReturn("5");
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(600L);

        assertThatThrownBy(() -> limiter.checkBlocked("admin", "1.2.3.4"))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(e -> assertThat(((RateLimitExceededException) e).getRetryAfterSeconds())
                        .isEqualTo(600L));
    }

    @Test
    void counterWithoutTtlFallsBackToFullWindow() {
        // TTL 已丢（-1 / -2）时不能把 Retry-After 报成 0，否则前端会立刻重试
        when(valueOperations.get(anyString())).thenReturn("5");
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(-1L);

        assertThatThrownBy(() -> limiter.checkBlocked("admin", "1.2.3.4"))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(e -> assertThat(((RateLimitExceededException) e).getRetryAfterSeconds())
                        .isEqualTo(900L));
    }

    /**
     * IP 维度是**宽阈值辅助**：30 次才拒。10 次在账号维度会被拒、在 IP 维度不会——
     * 这条用"账号键为 0"把账号维度摘出去，单独验证 IP 维度既不误伤也没失效。
     */
    @Test
    void ipDimensionToleratesMoreFailuresThanAccountDimension() {
        when(valueOperations.get(ACCOUNT_KEY)).thenReturn("0");
        when(valueOperations.get(IP_KEY)).thenReturn("10");

        assertThatCode(() -> limiter.checkBlocked("admin", "1.2.3.4"))
                .doesNotThrowAnyException();
    }

    @Test
    void ipDimensionRejectsAtItsOwnThreshold() {
        when(valueOperations.get(ACCOUNT_KEY)).thenReturn("0");
        when(valueOperations.get(IP_KEY)).thenReturn("30");
        when(redisTemplate.getExpire(IP_KEY, TimeUnit.SECONDS)).thenReturn(300L);

        assertThatThrownBy(() -> limiter.checkBlocked("admin", "1.2.3.4"))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(e -> assertThat(((RateLimitExceededException) e).getRetryAfterSeconds())
                        .isEqualTo(300L));
    }

    /**
     * IP 为空（拿不到来源地址）时不能退化成"所有空 IP 共用一个键"，
     * 那会让毫不相干的人互相计数。降级为只做账号维度。
     */
    @Test
    void blankClientIpSkipsIpDimension() {
        when(valueOperations.get(anyString())).thenReturn(null);

        limiter.checkBlocked("admin", "  ");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, times(1)).get(keyCaptor.capture());
        assertThat(keyCaptor.getValue()).isEqualTo(ACCOUNT_KEY);
    }

    @Test
    void redisFailureFailsOpen() {
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("redis down"));

        // fail-open：Redis 故障时放行。拒绝会让 Redis 一抖等于全站登不进去
        assertThatCode(() -> limiter.checkBlocked("admin", "1.2.3.4"))
                .doesNotThrowAnyException();
    }

    @Test
    void recordFailureIncrementsBothDimensionsAtomically() {
        when(redisTemplate.execute(any(), anyList(), any(String.class))).thenReturn(1L);

        limiter.recordFailure("admin", "1.2.3.4");

        ArgumentCaptor<List<String>> keyCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> argCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate, times(2)).execute(any(), keyCaptor.capture(), argCaptor.capture());

        assertThat(keyCaptor.getAllValues())
                .containsExactly(List.of(ACCOUNT_KEY), List.of(IP_KEY));
        // TTL 由脚本在首次 INCR 时设置：INCR 与 EXPIRE 分开调用会出现"计数永不过期 → 永久锁定"
        assertThat(argCaptor.getAllValues()).containsOnly("900");
    }

    @Test
    void clearDeletesTheAccountCounterOnly() {
        limiter.clear("admin");

        verify(redisTemplate).delete(ACCOUNT_KEY);
        // IP 维度不能清：清了等于给攻击者留后门——他只要有一个自己的账号，
        // 就能靠反复登录成功把 IP 计数刷掉，撞库防护直接失效
        verify(redisTemplate, never()).delete(IP_KEY);
    }

    @Test
    void redisFailureOnRecordAndClearDoesNotPropagate() {
        when(redisTemplate.execute(any(), anyList(), any(String.class)))
                .thenThrow(new RuntimeException("redis down"));
        when(redisTemplate.delete(anyString())).thenThrow(new RuntimeException("redis down"));

        // 计数失败不能让一次正常的"密码错误"变成 500
        assertThatCode(() -> limiter.recordFailure("admin", "1.2.3.4"))
                .doesNotThrowAnyException();
        assertThatCode(() -> limiter.clear("admin"))
                .doesNotThrowAnyException();
    }

    /**
     * 这条锁的是"归一化"：DB 的 collation 是 utf8mb4_unicode_ci，` Admin ` 与 `admin`
     * 在库里是同一个用户。计数键若不做 trim + 小写，大小写变体各占一个键，5 次阈值会被拆成 15 次。
     */
    @Test
    void caseAndSpaceVariantsShareOneAccountCounter() {
        when(valueOperations.get(anyString())).thenReturn(null);

        limiter.checkBlocked(" Admin ", "1.2.3.4");
        limiter.checkBlocked("admin", "1.2.3.4");
        limiter.checkBlocked("ADMIN", "1.2.3.4");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, atLeastOnce()).get(keyCaptor.capture());

        assertThat(accountKeys(keyCaptor)).containsOnly(ACCOUNT_KEY);
    }

    /**
     * **分布式爆破**：一个账号、代理池多 IP，每个 IP 只试几次。
     * 账号维度必须把这些 IP 的失败汇总到同一个键上——否则就是「用户名 + IP」组合键那种漏法。
     */
    @Test
    void sameUsernameFromDifferentIpsSharesOneAccountCounter() {
        when(valueOperations.get(anyString())).thenReturn(null);

        limiter.checkBlocked("admin", "1.2.3.4");
        limiter.checkBlocked("admin", "5.6.7.8");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, atLeastOnce()).get(keyCaptor.capture());

        assertThat(accountKeys(keyCaptor)).containsOnly(ACCOUNT_KEY);
    }

    /**
     * **撞库**：泄露库拿来的多账号、各试一两次、来自同一个来源。
     * 每个账号的计数都攒不到阈值，只有 IP 维度看得见——IP 键必须把这些账号汇总起来。
     */
    @Test
    void differentAccountsFromSameIpShareOneIpCounter() {
        when(valueOperations.get(anyString())).thenReturn(null);

        limiter.checkBlocked("alice", "1.2.3.4");
        limiter.checkBlocked("bob", "1.2.3.4");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, times(4)).get(keyCaptor.capture());

        assertThat(ipKeys(keyCaptor)).containsOnly(IP_KEY);
    }

    @Test
    void overlongUsernameIsTruncatedToColumnWidth() {
        when(valueOperations.get(anyString())).thenReturn(null);

        limiter.checkBlocked("a".repeat(200), "1.2.3.4");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, atLeastOnce()).get(keyCaptor.capture());

        // users.username 是 varchar(50)：超长输入不该被原样拼进 key
        assertThat(accountKeys(keyCaptor))
                .containsOnly("login:fail:account:" + "a".repeat(50));
    }

    private List<String> accountKeys(ArgumentCaptor<String> captor) {
        return captor.getAllValues().stream()
                .filter(k -> k.startsWith("login:fail:account:"))
                .toList();
    }

    private List<String> ipKeys(ArgumentCaptor<String> captor) {
        return captor.getAllValues().stream()
                .filter(k -> k.startsWith("login:fail:ip:"))
                .toList();
    }
}
