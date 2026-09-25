package com.example.starter.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 幂等指纹工具：对 requestId 与全部业务字段做 SHA-256。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 计算指纹，字段按传入顺序以竖线连接后哈希。
     */
    public static String of(String... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
