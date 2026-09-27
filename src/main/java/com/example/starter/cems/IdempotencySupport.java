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
        return sha256(canonical);
    }

    /**
     * 结构化无歧义指纹：字段名与标量值均以“长度前缀:”编码，null 以独立标记表示，
     * 集合/Map/数组递归编码并带类型与元素个数标记。
     * 长度前缀保证即使合法文本包含 ':'、';'、'='、'|'、空串或跨行，也不会与字段边界混淆。
     * 字段按给定 Map 的迭代顺序写入（调用方用 LinkedHashMap 固定语义顺序）。
     */
    public String fingerprintStructured(String requestId, Map<String, ?> businessFields) {
        StringBuilder canonical = new StringBuilder();
        canonical.append("rid=").append(token(requestId));
        if (businessFields != null) {
            businessFields.forEach((name, value) -> {
                canonical.append(';').append(token(name)).append('=');
                appendValue(canonical, value);
            });
        }
        return sha256(canonical.toString());
    }

    /** 递归追加一个值：标量走长度前缀；集合带类型+元素个数并逐项递归，杜绝拼接歧义。 */
    private void appendValue(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Map<?, ?> map) {
            out.append("map{").append(map.size()).append('}');
            map.forEach((k, v) -> {
                out.append('[').append(token(String.valueOf(k))).append('=');
                appendValue(out, v);
                out.append(']');
            });
        } else if (value instanceof java.util.Collection<?> collection) {
            out.append("col[").append(collection.size()).append(']');
            for (Object element : collection) {
                out.append('<');
                appendValue(out, element);
                out.append('>');
            }
        } else if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            out.append("arr[").append(length).append(']');
            for (int i = 0; i < length; i++) {
                out.append('<');
                appendValue(out, java.lang.reflect.Array.get(value, i));
                out.append('>');
            }
        } else {
            out.append("val:").append(token(String.valueOf(value)));
        }
    }

    /** 长度前缀令牌：{长度}:{原文}；空串为 "0:"，与缺字段/null 可区分。 */
    private static String token(String value) {
        String safe = value == null ? "" : value;
        return safe.length() + ":" + safe;
    }

    private static String sha256(String canonical) {
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
