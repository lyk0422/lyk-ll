package com.example.starter.pension.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 幂等指纹工具：对 requestId 与全部业务字段计算 SHA-256，用于同键异参冲突判定。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 计算 SHA-256 指纹。
     *
     * @param parts 参与指纹的全部业务字段，按固定顺序拼接
     * @return 64 位十六进制指纹
     */
    public static String sha256(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                String safe = part == null ? "" : part;
                digest.update(safe.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '|');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", ex);
        }
    }
}
