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
     * 以 "key=value" 片段计算规范化文本的 SHA-256 十六进制摘要。
     * 调用方须按固定顺序传入全部业务字段，null 以 "null" 参与。
     * 每段以 "字符数:内容;" 长度前缀编码，字段边界无歧义：
     * 合法文本中即使含有分隔符（空格、冒号、分号等）也不会与别的字段组合混淆。
     */
    public static String of(String... keyValues) {
        StringBuilder sb = new StringBuilder();
        for (String kv : keyValues) {
            String part = kv == null ? "null" : kv;
            sb.append(part.length()).append(':').append(part).append(';');
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
