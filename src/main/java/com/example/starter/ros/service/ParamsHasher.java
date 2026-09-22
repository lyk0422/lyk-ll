package com.example.starter.ros.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 请求参数归一化与 SHA-256 哈希工具，用于同一 requestId 异参重放判定。
 * 归一化仅依赖参数语义（集合排序），不受字段顺序或集合顺序影响。
 */
public final class ParamsHasher {

    private ParamsHasher() {
    }

    /** 计算归一化字符串的 SHA-256 十六进制摘要。 */
    public static String sha256(String normalized) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
