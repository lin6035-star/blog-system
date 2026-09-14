package com.hailin.blogsystem.component;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 敏感配置启动校验。
 *
 * 设计稿：docs/security/sensitive-config-externalization-design.md
 *
 * <p><b>为什么需要它</b>：application.yml 里保留了本地开发默认值（123456 / 01star / *-dev-*），
 * 换取"克隆即可启动"。代价是存在一种危险情形——生产 profile 下某项忘了覆盖，会
 * <b>静默</b>用开发值跑起来（最严重的是 JWT secret：任何人都能用公开的 dev 值伪造 token）。
 * Spring 的占位符机制只能管"缺配置"，管不了"配置在、但值是弱值"，这个缺口由本类补上。
 *
 * <p><b>职责边界</b>：
 * <ul>
 *   <li>存在性 → Spring 占位符。application-prod.yml 里写 {@code ${VAR}} 而非 {@code ${VAR:default}}，
 *       缺失时占位符解析失败，启动即终止。</li>
 *   <li>质量 → 本类。拒绝开发默认值 + 强制 JWT secret 长度下限。</li>
 * </ul>
 *
 * <p><b>日志纪律</b>：只输出配置键与状态，绝不输出配置值。
 */
@Component
public class SensitiveConfigStartupValidator {

    private static final Logger log = LoggerFactory.getLogger(SensitiveConfigStartupValidator.class);

    /** JWT secret 长度下限。HS256 的安全性完全取决于该值的熵，短 secret 可被离线爆破。 */
    private static final int MIN_JWT_SECRET_LENGTH = 32;

    /**
     * 开发默认值与已知示例值——出现在生产环境即视为配置错误。
     * 新增敏感项默认值时必须同步登记到这里，SensitiveConfigStartupValidatorTests 会锁住覆盖范围。
     */
    private static final Set<String> DEV_DEFAULTS = Set.of(
            "123456",                                      // spring.datasource.password
            "01star",                                      // spring.data.redis.password
            "blog-system-dev-secret-change-later",         // jwt.secret
            "change_to_a_random_string_at_least_32_chars"  // deploy/blog-system.env.example 里的占位符
    );

    /** 参与校验与启动日志的敏感项：配置键 → 日志用短名（短名不含值，可安全打印） */
    private static final Map<String, String> SENSITIVE_KEYS = new LinkedHashMap<>();

    static {
        SENSITIVE_KEYS.put("jwt.secret", "jwt.secret");
        SENSITIVE_KEYS.put("spring.datasource.password", "db.password");
        SENSITIVE_KEYS.put("spring.data.redis.password", "redis.password");
        SENSITIVE_KEYS.put("github.oauth.client-secret", "github.client-secret");
    }

    private final Environment environment;

    public SensitiveConfigStartupValidator(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        boolean prod = isProdProfile();
        List<String> weakKeys = new ArrayList<>();
        StringBuilder summary = new StringBuilder();

        for (Map.Entry<String, String> entry : SENSITIVE_KEYS.entrySet()) {
            String value = environment.getProperty(entry.getKey());
            if (summary.length() > 0) {
                summary.append(" | ");
            }
            summary.append(entry.getValue()).append('=').append(describe(value));
            if (value != null && DEV_DEFAULTS.contains(value)) {
                weakKeys.add(entry.getValue());
            }
        }

        // 启动即可见：本次到底用的是注入值还是开发默认值。排查生产问题时这一行省掉大量猜测。
        log.info("[敏感配置] profile={} | {}", currentProfile(), summary);

        String jwtSecret = environment.getProperty("jwt.secret");
        if (jwtSecret != null && jwtSecret.length() < MIN_JWT_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "jwt.secret 长度不足 " + MIN_JWT_SECRET_LENGTH + " 字符（当前 " + jwtSecret.length()
                            + "）。短 secret 可被离线爆破，攻击者据此可伪造任意用户身份。"
                            + "请改用随机长串（此处不回显配置值）。");
        }

        if (prod && !weakKeys.isEmpty()) {
            throw new IllegalStateException(
                    "生产环境检测到敏感配置仍是开发默认值：" + weakKeys
                            + "。请通过环境变量注入真实值，清单见 deploy/blog-system.env.example。");
        }
    }

    /** 只描述状态，不返回值本身——日志与异常信息都经此出口，从根上杜绝敏感值外泄 */
    private String describe(String value) {
        if (value == null || value.isBlank()) {
            return "未配置";
        }
        return DEV_DEFAULTS.contains(value) ? "开发默认值" : "已配置";
    }

    private boolean isProdProfile() {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equals(profile)) {
                return true;
            }
        }
        return false;
    }

    private String currentProfile() {
        String[] profiles = environment.getActiveProfiles();
        return profiles.length == 0 ? "default" : String.join(",", profiles);
    }
}
