package com.example.starter.marpol.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

/**
 * 幂等指纹：覆盖 requestId 与全部业务字段，异参即不同指纹。
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    public static String submit(String requestId, String shipId, String voyageNo, String type,
                                BigDecimal latitude, BigDecimal longitude, Instant eventTime,
                                BigDecimal quantity, String unit) {
        return sha256(String.join("|",
                "SUBMIT", requestId, shipId, voyageNo, type,
                normalized(latitude), normalized(longitude), eventTime.toString(),
                normalized(quantity), unit));
    }

    public static String amend(String requestId, String recordNo, Integer basedOnVersion,
                               String shipId, String voyageNo, String type,
                               BigDecimal latitude, BigDecimal longitude,
                               Instant eventTime, BigDecimal quantity, String unit) {
        return sha256(String.join("|",
                "AMEND", requestId, recordNo, String.valueOf(basedOnVersion), shipId, voyageNo,
                type, normalized(latitude), normalized(longitude), eventTime.toString(),
                normalized(quantity), unit));
    }

    private static String normalized(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
