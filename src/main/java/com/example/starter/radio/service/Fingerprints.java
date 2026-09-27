package com.example.starter.radio.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 幂等指纹：覆盖 requestId 与全部业务字段的 SHA-256。
 * 同键同参（指纹一致）返回首次结果；同键异参（指纹不一致）返回 409。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 计算指纹。字段按给定顺序以 '|' 连接后取 SHA-256，调用方必须传入 requestId 及全部业务字段。
     */
    public static String of(Object... fields) {
        StringBuilder canonical = new StringBuilder();
        for (Object field : fields) {
            canonical.append(String.valueOf(field)).append('|');
        }
        return sha256(canonical);
    }

    /**
     * 结构化无歧义指纹：每个字段带类型标记与长度前缀，字段间以 '|' 分隔。
     * 合法文本即使包含分隔符 '|' 或冒号也不会与字段边界混淆，例如 ["a|b"] 与 ["a","b"] 编码不同。
     * 集合以元素个数作前缀并逐项结构化编码；数字与布尔量不含分隔符，直接渲染。
     */
    public static String structured(Object... fields) {
        StringBuilder canonical = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                canonical.append('|');
            }
            appendStructured(canonical, fields[i]);
        }
        return sha256(canonical);
    }

    private static void appendStructured(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("n:");
        } else if (value instanceof String s) {
            sb.append("s:").append(s.length()).append(':').append(s);
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append("x:").append(value);
        } else if (value instanceof Enum<?> e) {
            sb.append("e:").append(e.name().length()).append(':').append(e.name());
        } else if (value instanceof List<?> list) {
            sb.append("l:").append(list.size()).append(':');
            for (Object element : list) {
                appendStructured(sb, element);
            }
        } else if (value instanceof Object[] array) {
            sb.append("a:").append(array.length).append(':');
            for (Object element : array) {
                appendStructured(sb, element);
            }
        } else {
            String s = String.valueOf(value);
            sb.append("s:").append(s.length()).append(':').append(s);
        }
    }

    private static String sha256(StringBuilder canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
