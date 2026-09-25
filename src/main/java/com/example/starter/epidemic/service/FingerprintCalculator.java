package com.example.starter.epidemic.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 幂等指纹：覆盖 requestId 与全部业务字段。字段按固定顺序拼接，空值归一为空串。
 */
@Component
public class FingerprintCalculator {

    public String sha256(Object... parts) {
        String joined = String.join("|",
                java.util.Arrays.stream(parts).map(p -> p == null ? "" : String.valueOf(p)).toList());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(joined.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
