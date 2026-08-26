package com.hailin.blogsystem.ai.workflow;

import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class WorkflowActionLock {

    private static final long LOCK_TTL_SECONDS = 120L;

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    if redis.call('get', KEYS[1]) == ARGV[1] then
                        return redis.call('del', KEYS[1])
                    else
                        return 0
                    end
                    """,
                    Long.class
            );

    private final StringRedisTemplate redisTemplate;

    public WorkflowActionLock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public LockHandle acquireOrThrow(Long runId) {
        if (runId == null) {
            throw new IllegalArgumentException("Workflow ID不能为空");
        }

        String key = RedisConstants.AI_WORKFLOW_ACTION_LOCK_KEY_PREFIX + runId;
        String token = UUID.randomUUID().toString();

        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(
                            key,
                            token,
                            LOCK_TTL_SECONDS,
                            TimeUnit.SECONDS
                    );

            if (!Boolean.TRUE.equals(acquired)) {
                throw new WorkflowActionBusyException();
            }

            return new LockHandle(runId, key, token);
        } catch (WorkflowActionBusyException e) {
            throw e;
        } catch (RuntimeException e) {
            /*
             * Redis 故障时暂时降级放行。
             * 后面的数据库 version 条件更新仍会阻止两个请求同时推进。
             */
            log.error(
                    "Workflow Redis 并发锁不可用，降级放行: runId={}",
                    runId,
                    e
            );

            return new LockHandle(runId, null, null);
        }
    }

    public void release(LockHandle handle) {
        if (handle == null
                || handle.key() == null
                || handle.token() == null) {
            return;
        }

        try {
            redisTemplate.execute(
                    UNLOCK_SCRIPT,
                    List.of(handle.key()),
                    handle.token()
            );
        } catch (RuntimeException e) {
            log.warn(
                    "Workflow Redis 锁释放失败: runId={}",
                    handle.runId(),
                    e
            );
        }
    }

    public record LockHandle(
            Long runId,
            String key,
            String token
    ) {
    }

    public static class WorkflowActionBusyException
            extends BusinessException {

        public WorkflowActionBusyException() {
            super(
                    BlogConstants.ErrorCode.CONFLICT,
                    "Workflow 正在处理中，请稍后刷新"
            );
        }
    }
}