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

@Component
public class JwtUtil {

    private final String secret;

    public JwtUtil(@Value("${jwt.secret}") String secret) {
        this.secret = secret;
    }
    private static final Duration EXPIRE_TIME = Duration.ofDays(7);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 生成 JWT token，payload 包含 userId 和过期时间 */
    public String generateToken(Long userId) {
        long expireAt = Instant.now().plus(EXPIRE_TIME).getEpochSecond();
        String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"userId\":" + userId + ",\"exp\":" + expireAt + "}");
        String unsignedToken = header + "." + payload;
        return unsignedToken + "." + sign(unsignedToken);
    }

    /**
     * 解析 token，验证签名和过期时间。
     * 验证通过返回 userId，失败抛出异常。
     */
    public Long parseToken(String token) {
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

        JsonNode payload;
        try {
            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            payload = objectMapper.readTree(payloadJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("token payload 解析失败", e);
        }

        long expireAt = payload.get("exp").asLong();
        if (Instant.now().getEpochSecond() > expireAt) {
            throw new IllegalArgumentException("token 已过期");
        }

        return payload.get("userId").asLong();
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
