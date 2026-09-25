package com.example.starter.invoice.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.StringJoiner;

/**
 * 幂等指纹工具：对 requestId 与全部业务字段的规范化串计算 SHA-256。
 */
public final class FingerprintUtil {

    private FingerprintUtil() {
    }

    /**
     * 计算指纹。字段按传入顺序以竖线拼接，null 以空串参与。
     */
    public static String fingerprint(String... fields) {
        StringJoiner joiner = new StringJoiner("|");
        for (String field : fields) {
            joiner.add(field == null ? "" : field);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(joiner.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
