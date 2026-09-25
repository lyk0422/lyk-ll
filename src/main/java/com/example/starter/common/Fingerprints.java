package com.example.starter.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 幂等指纹：覆盖 requestId 与全部业务字段的 SHA-256 摘要。
 * 同键同参命中首次结果，同键异参返回 409。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 计算指纹：字段按传入顺序以竖线连接，null 记为占位符，避免字段错位碰撞。
     *
     * @param fields requestId 及全部业务字段
     * @return 十六进制摘要
     */
    public static String of(Object... fields) {
        StringBuilder sb = new StringBuilder();
        for (Object field : fields) {
            sb.append(field == null ? "<null>" : field.toString()).append('|');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
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
