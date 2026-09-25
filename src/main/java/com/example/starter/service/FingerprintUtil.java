package com.example.starter.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等指纹工具：对 requestId 与全部业务字段的规范 JSON 求 SHA-256。
 */
public final class FingerprintUtil {

    private FingerprintUtil() {
    }

    public static String sha256(ObjectMapper objectMapper, String requestId, Object payload) {
        try {
            String canonical = objectMapper.writeValueAsString(new String[] {requestId,
                    objectMapper.writeValueAsString(payload)});
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("计算幂等指纹失败", e);
        }
    }
}
