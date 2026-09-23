package com.example.starter.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 请求参数指纹工具：对交接业务参数生成 SHA-256 十六进制指纹，
 * 用于同 requestId 不同参数的 409 冲突检测。requestId 本身是键，不参与指纹。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 生成交接请求业务参数指纹。
     */
    public static String handoverFingerprint(int expectedVersion, String custodian, String receiver,
                                             long handoverAt, long lastSequence) {
        String canonical = expectedVersion + "|" + custodian + "|" + receiver + "|"
                + handoverAt + "|" + lastSequence;
        return sha256Hex(canonical);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
