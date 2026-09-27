package com.example.starter.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;

/**
 * 幂等指纹工具：对 requestId 与全部业务字段计算 SHA-256。
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
     * 计算结构化指纹：每个字段以“长度:值”编码，集合以“大小[元素...”递归编码，空值编码为 -1:。
     * 长度前缀使编码无歧义，字段文本中合法出现的分隔符不会与结构混淆。
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
            sb.append("-1:");
            return;
        }
        if (field instanceof Collection<?> collection) {
            sb.append(collection.size()).append('[');
            for (Object element : collection) {
                encode(sb, element);
            }
            return;
        }
        String text = field.toString();
        sb.append(text.length()).append(':').append(text);
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
