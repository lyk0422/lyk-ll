package com.example.starter.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 幂等指纹工具：覆盖 requestId 与全部业务字段的规范化串行化后取 SHA-256。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 以 "key=value" 片段拼接规范化文本并计算 SHA-256 十六进制摘要。
     * 调用方须按固定顺序传入全部业务字段，null 以 "null" 参与。
     */
    public static String of(String... keyValues) {
        StringBuilder sb = new StringBuilder();
        for (String kv : keyValues) {
            sb.append(kv == null ? "null" : kv).append(' ');
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
