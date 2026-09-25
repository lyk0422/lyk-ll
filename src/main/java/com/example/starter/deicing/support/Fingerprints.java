package com.example.starter.deicing.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 幂等指纹工具：对幂等键与全部业务字段取 SHA-256。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * @param parts 参与指纹的业务字段（按固定顺序拼接，null 以空串表示）
     * @return SHA-256 十六进制摘要
     */
    public static String sha256(String... parts) {
        String joined = String.join("|", parts);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(joined.getBytes(StandardCharsets.UTF_8));
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
