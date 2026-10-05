package com.example.starter.deicing.service;

import com.example.starter.deicing.domain.DeicingType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

/**
 * 幂等指纹：覆盖 requestId 与全部业务字段。
 */
final class Fingerprints {

    private Fingerprints() {
    }

    static String sha256(String requestId, String flightNumber, String standCode,
                         DeicingType type, int requiredBays, int durationMinutes,
                         Instant targetTakeoffTime) {
        return sha256(requestId,
                flightNumber,
                standCode,
                type.name(),
                Integer.toString(requiredBays),
                Integer.toString(durationMinutes),
                Long.toString(targetTakeoffTime.toEpochMilli()));
    }

    /**
     * 通用指纹：按给定字段顺序拼接后取 SHA-256。
     */
    static String sha256(String... parts) {
        String canonical = String.join("|", parts);
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
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
