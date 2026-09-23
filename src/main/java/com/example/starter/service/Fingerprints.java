package com.example.starter.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 摘要工具：用于证据内容摘要与请求键指纹。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    public static String sha256Hex(byte[] content) {
        return HexFormat.of().formatHex(digest().digest(content));
    }

    /**
     * 对规范化字符串（字段以 '|' 分隔）计算指纹。
     */
    public static String sha256Hex(String canonical) {
        return sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
