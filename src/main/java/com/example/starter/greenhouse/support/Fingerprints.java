package com.example.starter.greenhouse.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 幂等指纹：SHA-256，覆盖 requestId 与全部业务字段。
 * 字段以  分隔并保持调用方给定顺序，避免有序字符串拼接歧义。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    public static String sha256(String requestId, Object... fields) {
        StringBuilder sb = new StringBuilder(requestId);
        for (Object field : fields) {
            sb.append('').append(field == null ? "" : field.toString());
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
