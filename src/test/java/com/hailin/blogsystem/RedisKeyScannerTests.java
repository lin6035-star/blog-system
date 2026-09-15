package com.hailin.blogsystem;

import com.hailin.blogsystem.component.RedisKeyScanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SCAN 扫描 / 批量删除（替代 KEYS 的那层封装）。
 *
 * 重点：删除走 UNLINK 而非 DEL；Redis 故障时不得把异常抛给调用方
 * （这些调用点原本都包在 try-catch 里，语义要保持一致）。
 */
@ExtendWith(MockitoExtension.class)
class RedisKeyScannerTests {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private Cursor<String> cursor;

    private RedisKeyScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new RedisKeyScanner(redisTemplate);
    }

    @Test
    void scanAndDeleteUnlinksEveryScannedKey() {
        givenScanReturns("article:list:page:1", "article:list:page:2");
        when(redisTemplate.unlink(anyList())).thenReturn(2L);

        long deleted = scanner.scanAndDelete("article:list:*");

        assertThat(deleted).isEqualTo(2L);
        verify(redisTemplate).unlink(List.of("article:list:page:1", "article:list:page:2"));
    }

    @Test
    void scanAndDeleteDoesNothingWhenNoKeyMatches() {
        givenScanReturns();

        assertThat(scanner.scanAndDelete("article:list:*")).isZero();
        verify(redisTemplate, never()).unlink(anyList());
    }

    @Test
    void scanAndDeleteSwallowsRedisFailure() {
        when(redisTemplate.scan(any(ScanOptions.class)))
                .thenThrow(new RuntimeException("redis down"));

        assertThat(scanner.scanAndDelete("article:list:*")).isZero();
    }

    @Test
    void scanReturnsEveryMatchedKey() {
        givenScanReturns("a", "b", "c");

        assertThat(scanner.scan("x*")).containsExactly("a", "b", "c");
    }

    @Test
    void scanReturnsEmptyListOnRedisFailure() {
        when(redisTemplate.scan(any(ScanOptions.class)))
                .thenThrow(new RuntimeException("redis down"));

        assertThat(scanner.scan("x*")).isEmpty();
    }

    /** 让 mock 游标依次吐出给定 key，吐完后 hasNext 返回 false */
    private void givenScanReturns(String... keys) {
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);

        if (keys.length == 0) {
            when(cursor.hasNext()).thenReturn(false);
            return;
        }

        Boolean[] hasNextSequence = new Boolean[keys.length + 1];
        Arrays.fill(hasNextSequence, 0, keys.length, true);
        hasNextSequence[keys.length] = false;

        when(cursor.hasNext())
                .thenReturn(hasNextSequence[0],
                        Arrays.copyOfRange(hasNextSequence, 1, hasNextSequence.length));
        when(cursor.next())
                .thenReturn(keys[0], Arrays.copyOfRange(keys, 1, keys.length));
    }
}
