package com.example.starter.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
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

    /**
     * 结构化无歧义指纹：类型标签 + UTF-8 字节长度前缀编码，字段按键名字典序排列。
     *
     * <p>与 {@link #sha256(Map)} 的区别在于即使合法文本值内含任意分隔符（如
     * {@code "|"、","、"="、":"、"{"、"["}）甚至伪造的类型标签，也无法与其他参数发生碰撞：
     * 读取方严格按声明长度消费字节。供新增写操作的幂等指纹使用。</p>
     */
    public static String sha256Structured(Map<String, Object> fields) {
        TreeMap<String, Object> sorted = new TreeMap<>(fields);
        StringBuilder sb = new StringBuilder();
        appendStructured(sb, sorted);
        return sha256Utf8(sb.toString());
    }

    @SuppressWarnings("unchecked")
    private static void appendStructured(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("N|");
            return;
        }
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> byKey = new TreeMap<>();
            map.forEach((k, v) -> byKey.put(String.valueOf(k), v));
            sb.append("M|").append(byKey.size()).append(':');
            for (Map.Entry<String, Object> e : byKey.entrySet()) {
                appendText(sb, e.getKey());
                appendStructured(sb, e.getValue());
            }
            return;
        }
        if (value instanceof List<?> list) {
            sb.append("L|").append(list.size()).append(':');
            for (Object item : list) {
                appendStructured(sb, item);
            }
            return;
        }
        if (value instanceof String || value instanceof Character || value instanceof Instant) {
            appendText(sb, value.toString());
            return;
        }
        if (value instanceof Boolean b) {
            appendTagged(sb, "B", b.toString());
            return;
        }
        if (value instanceof Number n) {
            appendTagged(sb, "I", n.toString());
            return;
        }
        // 其余类型退化为字符串（仍带长度前缀，无歧义）
        appendText(sb, String.valueOf(value));
    }

    private static void appendText(StringBuilder sb, String text) {
        appendTagged(sb, "S", text);
    }

    /** 标签 + UTF-8 字节长度 + ':' + 原文；长度前缀保证含分隔符文本也无歧义。 */
    private static void appendTagged(StringBuilder sb, String tag, String text) {
        sb.append(tag).append('|').append(text.getBytes(StandardCharsets.UTF_8).length)
                .append(':').append(text);
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
