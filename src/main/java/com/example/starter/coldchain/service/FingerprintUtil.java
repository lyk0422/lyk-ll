package com.example.starter.coldchain.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;

/**
 * 交接请求指纹：对业务关键参数做 SHA-256，用于同 requestId 异参（含版本或 lastSequence 变化）判 409。
 */
public final class FingerprintUtil {

    private FingerprintUtil() {
    }

    /**
     * 计算请求指纹。
     */
    public static String sha256(String shipmentId, int expectedVersion, String receiver,
                                LocalDateTime handoverAt, long lastSequence) {
        String canonical = String.join("|",
                shipmentId,
                Integer.toString(expectedVersion),
                receiver,
                handoverAt.toString(),
                Long.toString(lastSequence));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
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
