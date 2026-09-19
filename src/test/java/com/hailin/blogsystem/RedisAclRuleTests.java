package com.hailin.blogsystem;

import io.lettuce.core.AclCategory;
import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 生产 ACL 规则（`deploy/redis.conf.example` 给应用账号的 `+@all -@dangerous`）可用性验证。
 *
 * **它锁的是什么**：规则改严时最危险的不是"某条命令报错"，而是**应用要用的命令被误伤**——
 * 症状会散落在业务各处，很难第一时间归因到 ACL。这里用真 Lettuce 客户端 + 探测账号，
 * 把应用实际用到的每类命令跑一遍，同时断言危险命令仍被拒。
 *
 * **它不锁什么**：`CLIENT SETINFO`（Redis 7.2+ 才有的 Lettuce 握手命令）。
 * 本地 Redis 是 6.2，压根不会发这条命令，测了也是空跑——留个"看起来验证过"的用例
 * 比没有更糟。这一项的结论取自源码而非实测：`RedisHandshake.applyConnectionMetadataSafely()`
 * 用 `handle((v, t) -> { log.debug(...); return null; })` 把元数据命令的失败整个吞掉，
 * 因此 SETINFO 即使被 ACL 拒（NOPERM）也不影响建连。依据与出处见
 * `docs/redis/redis-capability-design.md` §5.8 的 ACL 一节。
 *
 * 流程：管理员账号建临时账号 → Lettuce 连它 → 跑命令 → 删账号。
 * 全程只用 `ACL SETUSER` / `ACL DELUSER` 运行时命令，不落盘（不调 `ACL SAVE`）。
 */
@SpringBootTest
class RedisAclRuleTests {

    private static final String PROBE_USER = "acl_rule_probe";
    private static final String PROBE_PASS = "probe-pass-123";

    @Value("${spring.data.redis.host}")
    private String host;

    @Value("${spring.data.redis.port}")
    private int port;

    @Value("${spring.data.redis.password}")
    private String password;

    @AfterEach
    void dropProbeUser() {
        // 幂等：账号不存在时命令返回 0，不报错
        withAdmin(cmd -> cmd.aclDeluser(PROBE_USER));
    }

    @Test
    void appRuleAllowsAppCommandsAndBlocksDangerousOnes() {
        withAdmin(cmd -> cmd.aclSetuser(PROBE_USER, appRule()));

        RedisClient client = RedisClient.create(
                "redis://" + PROBE_USER + ":" + PROBE_PASS + "@" + host + ":" + port + "/1");
        try (StatefulRedisConnection<String, String> conn = client.connect()) {
            RedisCommands<String, String> cmd = conn.sync();

            // 应用实际用到的每类命令各取一个代表：String / Set / ZSet / TTL / SCAN / Lua
            assertThat(cmd.set("test:acl:probe", "v")).isEqualTo("OK");
            assertThat(cmd.get("test:acl:probe")).isEqualTo("v");
            cmd.sadd("test:acl:set", "a");
            cmd.zadd("test:acl:zset", 1.0, "m");
            cmd.expire("test:acl:set", 60);
            cmd.scan(ScanArgs.Builder.limit(10));
            cmd.eval("return 1", ScriptOutputType.INTEGER);
            cmd.del("test:acl:probe", "test:acl:set", "test:acl:zset");

            // 危险命令必须仍然被拒——ACL 的意义就在这里
            assertThatThrownBy(() -> cmd.keys("*")).hasMessageContaining("NOPERM");
            assertThatThrownBy(() -> cmd.flushall()).hasMessageContaining("NOPERM");
        } finally {
            client.shutdown();
        }
    }

    /**
     * 对应 `deploy/redis.conf.example` 给应用账号的规则：`on #<sha256> ~* +@all -@dangerous`。
     * 返回值是 {@link AclSetuserArgs} 而非 Builder——Lettuce 的链式方法挂在实例上，
     * `Builder` 只提供 `on()` / `off()` 这类静态入口。
     */
    private AclSetuserArgs appRule() {
        return AclSetuserArgs.Builder.on()
                .addPassword(PROBE_PASS)
                .allKeys()
                .allCommands()
                .removeCategory(AclCategory.DANGEROUS);
    }

    /** 用管理员（default）账号执行一次 ACL 操作，跑完即断开 */
    private <T> T withAdmin(Function<RedisCommands<String, String>, T> action) {
        RedisClient admin = RedisClient.create(
                "redis://:" + password + "@" + host + ":" + port + "/1");
        try (StatefulRedisConnection<String, String> conn = admin.connect()) {
            return action.apply(conn.sync());
        } finally {
            admin.shutdown();
        }
    }
}
