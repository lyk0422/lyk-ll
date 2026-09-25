package com.example.starter.pension.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 幂等指纹工具：对 requestId 与全部业务字段的规范化串做 SHA-256。
 */
public final class FingerprintUtil {

    private FingerprintUtil() {
    }

    /**
     * @param parts 参与指纹的业务字段，顺序需稳定
     * @return 64 位十六进制 SHA-256 摘要
     */
    public static String sha256(String... parts) {
        String canonical = String.join("|", parts);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
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
