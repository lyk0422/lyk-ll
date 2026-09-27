package com.example.starter.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 请求指纹：覆盖 requestId 与全部业务字段的 SHA-256，用于同键异参冲突判定。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    public static String sha256(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    /**
     * 结构化指纹：各字段先按“长度:内容”长度前缀编码再拼接取 SHA-256，
     * 字段值含分隔符（如 |、:）也不会与相邻字段混淆；null 以 -1: 表示。
     */
    public static String struct(String... parts) {
        StringBuilder canonical = new StringBuilder();
        for (String part : parts) {
            if (part == null) {
                canonical.append("-1:");
            } else {
                canonical.append(part.length()).append(':').append(part);
            }
        }
        return sha256(canonical.toString());
    }
}
