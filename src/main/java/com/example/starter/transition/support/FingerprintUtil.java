package com.example.starter.transition.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 幂等指纹工具：对 requestId 与全部业务字段的规范化 JSON 计算 SHA-256。
 * 字段按名称排序，保证同参对象指纹稳定。
 */
public final class FingerprintUtil {

    private static final JsonMapper ORDERED = JsonMapper.builder()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .build();

    private FingerprintUtil() {
    }

    public static String sha256(String requestId, Object payload) {
        String canonical;
        try {
            String payloadJson = ORDERED.writeValueAsString(payload);
            canonical = requestId + "|" + payloadJson;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("指纹计算失败", e);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
