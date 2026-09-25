package com.example.starter.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 幂等指纹：SHA-256( requestId | 请求类型 | 业务字段规范化 JSON )。
 * 指纹同时覆盖 requestId 与全部业务字段，确保“同键同参重放、同键异参 409”。
 */
@Component
public class FingerprintHasher {

    private final ObjectMapper objectMapper;

    public FingerprintHasher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String hash(String requestId, String requestKind, Object businessPayload) {
        String canonical;
        try {
            canonical = objectMapper.writeValueAsString(businessPayload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等指纹序列化失败", e);
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
        digest.update(requestId.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '|');
        digest.update(requestKind.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '|');
        digest.update(canonical.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }
}
