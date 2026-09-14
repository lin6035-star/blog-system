package com.hailin.blogsystem;

import com.hailin.blogsystem.component.SensitiveConfigStartupValidator;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class SensitiveConfigStartupValidatorTests {

    private static final String STRONG_SECRET = "a-random-production-secret-long-enough-for-hs256";
    private static final String DEV_SECRET = "blog-system-dev-secret-change-later";
    private static final String ENV_EXAMPLE_PLACEHOLDER = "change_to_a_random_string_at_least_32_chars";

    private MockEnvironment environment(String jwtSecret) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("jwt.secret", jwtSecret);
        return environment;
    }

    private void validate(MockEnvironment environment) {
        new SensitiveConfigStartupValidator(environment).validate();
    }

    @Test
    void passesWhenProdInjectsRealValues() {
        MockEnvironment env = environment(STRONG_SECRET);
        env.setProperty("spring.datasource.password", "a-real-db-password");
        env.setProperty("spring.data.redis.password", "a-real-redis-password");
        env.setProperty("github.oauth.client-secret", "a-real-client-secret");
        env.setActiveProfiles("prod");

        assertThatCode(() -> validate(env)).doesNotThrowAnyException();
    }

    @Test
    void allowsDevDefaultsOutsideProd() {
        MockEnvironment env = environment(DEV_SECRET);
        env.setProperty("spring.datasource.password", "123456");
        env.setProperty("spring.data.redis.password", "01star");

        assertThatCode(() -> validate(env)).doesNotThrowAnyException();
    }

    @Test
    void rejectsDevDefaultsInProd() {
        MockEnvironment env = environment(DEV_SECRET);
        env.setProperty("spring.datasource.password", "123456");
        env.setActiveProfiles("prod");

        assertThatThrownBy(() -> validate(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.secret")
                .hasMessageContaining("db.password");
    }

    @Test
    void rejectsShortJwtSecret() {
        MockEnvironment env = environment("too-short");

        assertThatThrownBy(() -> validate(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.secret");
    }

    @Test
    void neverEchoesSecretValueInErrorMessage() {
        String shortSecret = "abc123";
        MockEnvironment env = environment(shortSecret);

        Throwable thrown = catchThrowable(() -> validate(env));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(thrown.getMessage()).doesNotContain(shortSecret);
    }

    @Test
    void rejectsEnvExamplePlaceholderInProd() {
        MockEnvironment env = environment(ENV_EXAMPLE_PLACEHOLDER);
        env.setActiveProfiles("prod");

        assertThatThrownBy(() -> validate(env)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void skipsLengthCheckWhenSecretAbsent() {
        // 存在性由 Spring 占位符负责（prod 下 ${JWT_SECRET} 无默认值），本类不重复承担，
        // 否则同一问题会在两处各报一次错，排查时反而要多读一段堆栈
        MockEnvironment env = new MockEnvironment();

        assertThatCode(() -> validate(env)).doesNotThrowAnyException();
    }
}
