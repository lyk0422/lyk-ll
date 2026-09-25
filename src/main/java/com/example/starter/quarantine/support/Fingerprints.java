package com.example.starter.quarantine.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 幂等指纹工具：对命令键与全部业务字段做 SHA-256。字段以 NUL 分隔，避免拼接歧义。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    public static String sha256(Object... parts) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
        for (Object part : parts) {
            digest.update(String.valueOf(part).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
