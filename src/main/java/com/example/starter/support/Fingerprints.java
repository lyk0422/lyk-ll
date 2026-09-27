package com.example.starter.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 幂等指纹工具：对 requestId 与全部业务字段的拼接计算 SHA-256。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 计算指纹。字段按传入顺序以竖线拼接，空值按空串处理。
     */
    public static String of(Object... fields) {
        StringBuilder sb = new StringBuilder();
        for (Object field : fields) {
            sb.append(field == null ? "" : field.toString()).append('|');
        }
        return sha256(sb.toString());
    }

    /**
     * 结构化无歧义指纹：每个标量带类型标记，字符串带字节长度前缀，集合带元素计数，
     * 因此合法文本即使包含分隔符、数字或括号，也无法与其他参数组合产生相同编码。
     * 例如 ["ab|c"] 与 ["ab", "c"]、整数 12 与字符串 "12" 的编码均不同。
     */
    public static String structured(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object part : parts) {
            sb.append(canonical(part)).append(';');
        }
        return sha256(sb.toString());
    }

    private static String canonical(Object value) {
        if (value == null) {
            return "z";
        }
        if (value instanceof String s) {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            return "s" + bytes.length + ":" + s;
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("l").append(list.size()).append(":");
            for (Object element : list) {
                sb.append(canonical(element));
            }
            return sb.toString();
        }
        if (value instanceof Integer || value instanceof Long) {
            return "i" + value;
        }
        if (value instanceof Number) {
            return "d" + value;
        }
        if (value instanceof Boolean b) {
            return "b" + b;
        }
        if (value instanceof Enum<?> e) {
            return "e" + e.name();
        }
        // 时间等值类型采用其 ISO/规范字符串，仍以类型标记与普通字符串区分
        String s = value.toString();
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        return "x" + bytes.length + ":" + s;
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
