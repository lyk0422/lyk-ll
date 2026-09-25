package com.example.starter.cems;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等支持：指纹覆盖 requestId 与全部业务字段（按字段名排序后规范化），
 * 同键同参回放首次结果，同键异参由调用方抛出 409。
 */
@Component
public class IdempotencySupport {

    private final ObjectMapper objectMapper;

    public IdempotencySupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 计算 SHA-256 指纹：requestId + 按 key 排序后的业务字段。 */
    public String fingerprint(String requestId, Map<String, ?> businessFields) {
        Map<String, Object> sorted = new TreeMap<>();
        if (businessFields != null) {
            sorted.putAll(businessFields);
        }
        String canonical = "requestId=" + nullToEmpty(requestId) + "|" + sorted;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public String toJson(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("幂等结果序列化失败", e);
        }
    }

    public <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等结果反序列化失败", e);
        }
    }

    /** 反序列化带泛型的记录类型（如 RecomputeResult 含 List）。 */
    public <T> T fromJson(String json, JavaType type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等结果反序列化失败", e);
        }
    }

    public JavaType listType(Class<?> element) {
        return objectMapper.getTypeFactory().constructCollectionType(java.util.List.class, element);
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
