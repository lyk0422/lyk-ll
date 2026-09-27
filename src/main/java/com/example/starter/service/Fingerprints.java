package com.example.starter.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 结构化幂等指纹：长度前缀编码保证无歧义，
 * 合法文本即使包含分隔符（如 "|"、":"）也不会与多字段拼接混淆。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 计算指纹：每个字段编码为 "长度:内容"（null 编码为 "-1:"），拼接后取 SHA-256。
     */
    public static String of(String... parts) {
        StringBuilder canonical = new StringBuilder();
        for (String part : parts) {
            if (part == null) {
                canonical.append("-1:");
            } else {
                canonical.append(part.length()).append(':').append(part);
            }
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
