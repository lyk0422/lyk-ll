package com.example.starter.ventilation.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 幂等指纹：覆盖 requestId、请求类型与全部业务字段，同键异参据此判定 409。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /** 计算 SHA-256 十六进制指纹，字段以 '|' 分隔并保持固定顺序。 */
    public static String of(String... fields) {
        String raw = String.join("|", fields);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
