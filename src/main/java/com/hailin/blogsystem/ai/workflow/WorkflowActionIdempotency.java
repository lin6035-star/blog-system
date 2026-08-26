package com.hailin.blogsystem.ai.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.entity.vo.AiWorkflowRunVO;
import com.hailin.blogsystem.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class WorkflowActionIdempotency {

    private static final long RESULT_TTL_HOURS = 24L;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public WorkflowActionIdempotency(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public String fingerprint(String action, String payload) {
        return sha256(
                action + "\n" + (payload == null ? "" : payload)
        );
    }

    public AiWorkflowRunVO get(
            Long userId,
            Long runId,
            String action,
            String idempotencyKey,
            String fingerprint
    ) {
        if (isBlank(idempotencyKey)) {
            return null;
        }

        try {
            String raw = redisTemplate.opsForValue()
                    .get(buildKey(userId, runId, action, idempotencyKey));

            if (raw == null || raw.isBlank()) {
                return null;
            }

            CachedActionResult cached =
                    objectMapper.readValue(raw, CachedActionResult.class);

            if (!fingerprint.equals(cached.fingerprint())) {
                throw new IdempotencyKeyConflictException();
            }

            return cached.result();
        } catch (IdempotencyKeyConflictException e) {
            throw e;
        } catch (RuntimeException | JsonProcessingException e) {
            log.error(
                    "读取 Workflow 幂等结果失败: runId={}, action={}",
                    runId,
                    action,
                    e
            );
            return null;
        }
    }

    public void save(
            Long userId,
            Long runId,
            String action,
            String idempotencyKey,
            String fingerprint,
            AiWorkflowRunVO result
    ) {
        if (isBlank(idempotencyKey) || result == null) {
            return;
        }

        try {
            String value = objectMapper.writeValueAsString(
                    new CachedActionResult(fingerprint, result)
            );

            redisTemplate.opsForValue().set(
                    buildKey(userId, runId, action, idempotencyKey),
                    value,
                    RESULT_TTL_HOURS,
                    TimeUnit.HOURS
            );
        } catch (RuntimeException | JsonProcessingException e) {
            // 幂等缓存故障不影响主业务
            log.error(
                    "保存 Workflow 幂等结果失败: runId={}, action={}",
                    runId,
                    action,
                    e
            );
        }
    }

    private String buildKey(
            Long userId,
            Long runId,
            String action,
            String idempotencyKey
    ) {
        return RedisConstants.AI_WORKFLOW_ACTION_IDEMPOTENCY_KEY_PREFIX
                + userId + ":"
                + runId + ":"
                + action + ":"
                + sha256(idempotencyKey);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] bytes = digest.digest(
                    value.getBytes(StandardCharsets.UTF_8)
            );

            StringBuilder result = new StringBuilder();
            for (byte b : bytes) {
                result.append(String.format("%02x", b));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("无法计算幂等 Key", e);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record CachedActionResult(
            String fingerprint,
            AiWorkflowRunVO result
    ) {
    }

    public static class IdempotencyKeyConflictException
            extends BusinessException {

        public IdempotencyKeyConflictException() {
            super(
                    BlogConstants.ErrorCode.CONFLICT,
                    "Idempotency-Key 已用于其他 Workflow 请求"
            );
        }
    }
}
