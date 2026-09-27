package com.example.starter.pump.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 幂等指纹工具：对 requestId 与全部业务字段计算 SHA-256。
 *
 * <p>采用“字段长度:字段内容”的长度前缀结构化编码，字段边界无歧义：
 * 合法文本即使包含分隔符（如 {@code |}、{@code :}）也不会与别的字段组合撞指纹。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 计算指纹。入参顺序固定，null 以 {@code -1:} 参与摘要，与空串 {@code 0:} 区分，
     * 保证同参同指纹、异参异指纹。
     */
    public static String sha256(Object... parts) {
        StringBuilder joined = new StringBuilder();
        for (Object part : parts) {
            String text = part == null ? null : part.toString();
            if (text == null) {
                joined.append("-1:");
            } else {
                joined.append(text.length()).append(':').append(text);
            }
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(joined.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
