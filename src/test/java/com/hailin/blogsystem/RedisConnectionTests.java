package com.hailin.blogsystem;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RedisConnectionTests {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void canConnectToRedis() {
        stringRedisTemplate.opsForValue().set("test:redis", "hello");

        String value = stringRedisTemplate.opsForValue().get("test:redis");

        assertThat(value).isEqualTo("hello");
    }
}