package com.example.starter.pesticide;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 幂等指纹工具：对 requestId 与全部业务字段计算 SHA-256。
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    /**
     * 计算指纹；字段按传入顺序拼接，null 以空串参与。
     */
    public static String of(String... parts) {
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            joined.append(part == null ? "" : part).append('|');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(joined.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
