package com.example.starter.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 指纹工具：对 requestId、操作类型与全部业务字段计算 SHA-256。
 * 业务字段先由调用方序列化为规范化 JSON 字节，保证同参指纹一致。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 计算指纹：requestId 与操作类型显式入指纹，防止跨操作复用键。
     */
    public static String sha256(String requestId, String operation, byte[] normalizedBusinessJson) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        digest.update(requestId.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(operation.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(normalizedBusinessJson);
        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
