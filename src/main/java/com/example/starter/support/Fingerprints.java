package com.example.starter.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 幂等指纹：覆盖 requestId 与全部业务字段，字段按名字典序规范化后做 SHA-256。
 *
 * <p>不依赖 Jackson 的时间模块：标量统一 {@code String.valueOf}（Instant 取其 ISO-8601 文本），
 * 因此同一业务参数集合在任何进程下指纹一致，可复算。</p>
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    public static String sha256(Map<String, Object> fields) {
        TreeMap<String, Object> sorted = new TreeMap<>(fields);
        String canonical = canonical(sorted);
        return sha256Utf8(canonical);
    }

    public static String sha256Utf8(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("指纹计算失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static String canonical(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            TreeMap<String, Object> byKey = new TreeMap<>();
            map.forEach((k, v) -> byKey.put(String.valueOf(k), v));
            boolean first = true;
            for (Map.Entry<String, Object> e : byKey.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(e.getKey()).append('=').append(canonical(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(canonical(list.get(i)));
            }
            return sb.append(']').toString();
        }
        return value.getClass().getSimpleName() + "(" + value + ")";
    }
}
