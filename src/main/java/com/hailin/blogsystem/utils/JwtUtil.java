package com.hailin.blogsystem.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * 手写 JWT（HS256）。
 *
 * payload 三个字段：
 * - userId：用户 ID
 * - jti：token 唯一标识，登出黑名单的键（`token:blacklist:{jti}`）
 * - exp：过期时间（秒）
 *
 * jti 是后加的字段。加之前签发的 token 没有它，解析出来是 null，因而无法被拉黑——
 * 这类老 token 登出后仍有效到 exp。属于可接受的过渡（重新登录一次即可拿到带 jti 的新 token），
 * 不为此写兼容分支。
 */
@Component
public class JwtUtil {

    /** token 载荷：一次解析拿到拦截器与登出需要的全部字段 */
    public record Payload(Long userId, String jti, long expireAtSeconds) {
    }

    private final String secret;

    public JwtUtil(@Value("${jwt.secret}") String secret) {
        this.secret = secret;
    }
    private static final Duration EXPIRE_TIME = Duration.ofDays(7);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 生成 JWT token，payload 包含 userId、jti 和过期时间 */
    public String generateToken(Long userId) {
        long expireAt = Instant.now().plus(EXPIRE_TIME).getEpochSecond();
        String jti = UUID.randomUUID().toString();
        String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"userId\":" + userId
                + ",\"jti\":\"" + jti + "\""
                + ",\"exp\":" + expireAt + "}");
        String unsignedToken = header + "." + payload;
        return unsignedToken + "." + sign(unsignedToken);
    }

    /**
     * 解析 token，验证签名和过期时间。
     * 验证通过返回载荷（userId / jti / 过期时间），失败抛出异常。
     */
    public Payload parsePayload(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token 为空");
        }

        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("token 格式错误");
        }

        String unsignedToken = parts[0] + "." + parts[1];
        String expectedSignature = sign(unsignedToken);

        if (!expectedSignature.equals(parts[2])) {
            throw new IllegalArgumentException("token 签名无效");
        }

        JsonNode claims;
        try {
            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            claims = objectMapper.readTree(payloadJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("token payload 解析失败", e);
        }

        long expireAt = claims.get("exp").asLong();
        if (Instant.now().getEpochSecond() > expireAt) {
            throw new IllegalArgumentException("token 已过期");
        }

        // 老 token 没有 jti：返回 null，调用方按"不可拉黑"处理
        JsonNode jtiNode = claims.get("jti");
        String jti = (jtiNode == null || jtiNode.isNull()) ? null : jtiNode.asText();

        return new Payload(claims.get("userId").asLong(), jti, expireAt);
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] signature = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (Exception e) {
            throw new IllegalStateException("failed to sign jwt token", e);
        }
    }

    private String base64Url(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
