package com.example.starter.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 幂等指纹工具：对 requestId 与全部业务字段做 SHA-256，
 * 同键同参重放返回首次结果，同键异参返回 409。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 计算幂等指纹，字段按声明顺序以竖线连接，null 以空串参与。
     */
    public static String of(String... fields) {
        MessageDigest digest = sha256();
        String joined = String.join("|", java.util.Arrays.stream(fields)
                .map(f -> f == null ? "" : f)
                .toArray(String[]::new));
        return HexFormat.of().formatHex(digest.digest(joined.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
