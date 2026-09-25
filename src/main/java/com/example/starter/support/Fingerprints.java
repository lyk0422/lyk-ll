package com.example.starter.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 幂等指纹工具：指纹覆盖 requestId 与全部业务字段，规范化后做 SHA-256。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 按顺序拼接业务字段（null 记为空串），计算 SHA-256。
     */
    public static String sha256(List<String> parts) {
        String canonical = String.join("", parts.stream().map(p -> p == null ? "" : p).toList());
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
