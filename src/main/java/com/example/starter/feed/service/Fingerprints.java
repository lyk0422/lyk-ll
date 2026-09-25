package com.example.starter.feed.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 幂等指纹：对操作类型 + requestId + 全部业务字段的规范化 JSON 求 SHA-256。
 * 对象字段统一按名字递归排序，数组保持业务顺序，BigDecimal 去尾零，
 * 保证同键同参指纹一致、任一业务字段变化指纹即变。
 */
public final class Fingerprints {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private Fingerprints() {
    }

    /** 计算指纹。operation 参与摘要，避免同一 requestId 跨操作串用。 */
    public static String of(String operation, String requestId, Object payload) {
        String canonical = operation + "|" + requestId + "|" + canonicalJson(payload);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 递归规范化：对象字段排序、数组保序、BigDecimal 去尾零后序列化为规范 JSON。 */
    private static String canonicalJson(Object payload) {
        try {
            // 先序列化再按“浮点→BigDecimal”读回，确保比例等小数不被退化为 double
            String json = MAPPER.writeValueAsString(payload);
            Object converted = MAPPER.readValue(json, Object.class);
            return MAPPER.writeValueAsString(normalize(converted));
        } catch (IllegalArgumentException | JsonProcessingException e) {
            throw new IllegalStateException("请求指纹序列化失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((key, child) -> sorted.put(String.valueOf(key), normalize(child)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> normalized = new ArrayList<>(list.size());
            for (Object child : list) {
                normalized.add(normalize(child));
            }
            return normalized;
        }
        if (value instanceof BigDecimal decimal) {
            // 0.001 与 0.0010 视为同参（仅用于指纹，业务比例比较本身也按 compareTo 口径）
            return decimal.stripTrailingZeros().toPlainString();
        }
        return value;
    }
}
