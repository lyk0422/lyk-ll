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
        return sha256Hex(sb.toString());
    }

    /**
     * 结构化无歧义指纹：每段以 "长度:内容" 长度前缀编码后拼接取 SHA-256，
     * null 编码为 "-1:"。合法文本即使含分隔符也不会与分段边界混淆。
     */
    public static String structured(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null) {
                sb.append("-1:");
            } else {
                sb.append(part.length()).append(':').append(part);
            }
        }
        return sha256Hex(sb.toString());
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
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
