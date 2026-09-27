package com.example.starter.runway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 结构化请求指纹：对每个字段做长度前缀编码后再哈希，
 * 字段值即使包含分隔符也不会产生歧义（如 ["a","b"] 与 ["a|b"] 指纹不同）。
 */
public final class StructuredFingerprint {

    private StructuredFingerprint() {
    }

    /**
     * 计算指纹：先写入字段个数，再逐字段写入 长度:值，最后取 SHA-256。
     */
    public static String of(List<String> parts) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(parts.size()).append(';');
        for (String part : parts) {
            String value = part == null ? "" : part;
            canonical.append(value.length()).append(':').append(value);
        }
        return sha256Hex(canonical.toString());
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
