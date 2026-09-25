package com.example.starter.hazmat.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.StringJoiner;

/**
 * 幂等指纹：覆盖 requestId 与全部业务字段的 SHA-256 摘要，同键同参指纹一致。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    public static String of(Object... fields) {
        StringJoiner joiner = new StringJoiner("|");
        for (Object field : fields) {
            joiner.add(String.valueOf(field));
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(joiner.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
