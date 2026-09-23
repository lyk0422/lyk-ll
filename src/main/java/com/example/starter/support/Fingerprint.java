package com.example.starter.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 请求指纹：对幂等请求的关键参数做规范化哈希（SHA-256），
 * 同键同参返回首次结果，同键异参返回 409。
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    /**
     * 计算规范化参数序列的 SHA-256 十六进制指纹。
     *
     * @param parts 参数片段；null 以空串参与，调用方保证字段顺序固定
     */
    public static String sha256(String kind, Iterable<String> parts) {
        StringBuilder canonical = new StringBuilder(kind);
        for (String part : parts) {
            canonical.append('|').append(part == null ? "" : part);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", ex);
        }
    }
}
