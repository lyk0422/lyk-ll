package com.example.starter.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
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
     * 计算结构化指纹：每个字段长度前缀编码（类型标记+字节长度+原文），集合递归编码并携带元素数。
     * 字段值本身包含分隔符时仍可无歧义区分，如 ["a|","b"] 与 ["a","|b"] 指纹不同。
     */
    public static String structured(Object... fields) {
        StringBuilder sb = new StringBuilder();
        for (Object field : fields) {
            encode(sb, field);
        }
        return sha256(sb.toString());
    }

    private static void encode(StringBuilder sb, Object field) {
        if (field == null) {
            sb.append("N;");
        } else if (field instanceof Iterable<?> items) {
            List<Object> list = new ArrayList<>();
            items.forEach(list::add);
            sb.append('L').append(list.size()).append(';');
            for (Object item : list) {
                encode(sb, item);
            }
            sb.append("E;");
        } else {
            String s = field.toString();
            sb.append('S').append(s.getBytes(StandardCharsets.UTF_8).length).append(';').append(s).append(';');
        }
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
