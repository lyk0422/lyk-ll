package com.example.starter.pump.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 幂等指纹工具：对 requestId 与全部业务字段计算 SHA-256。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 计算指纹。入参顺序固定，null 以空串参与摘要，保证同参同指纹。
     */
    public static String sha256(Object... parts) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                joined.append('|');
            }
            joined.append(parts[i] == null ? "" : parts[i].toString());
        }
        return sha256Hex(joined.toString());
    }

    /**
     * 结构化指纹：每个字段以“长度:内容”自描述编码后再拼接，null 记为 -1。
     * 字段值本身含分隔符也不会与字段边界混淆，保证无歧义。
     */
    public static String structured(Object... parts) {
        StringBuilder joined = new StringBuilder();
        for (Object part : parts) {
            String value = part == null ? null : part.toString();
            if (value == null) {
                joined.append("-1:");
            } else {
                joined.append(value.length()).append(':').append(value);
            }
            joined.append(';');
        }
        return sha256Hex(joined.toString());
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
