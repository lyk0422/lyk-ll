package com.example.starter.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.StringJoiner;

/**
 * 幂等指纹：对 requestId 与全部业务字段做规范化拼接后的 SHA-256。
 * 同键同参指纹一致返回首次结果，同键异参指纹不同返回 409。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 计算指纹。字段按调用方给定顺序以竖线拼接，null 规范化为空串。
     */
    public static String of(String... fields) {
        StringJoiner joiner = new StringJoiner("|");
        for (String field : fields) {
            joiner.add(field == null ? "" : field);
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
