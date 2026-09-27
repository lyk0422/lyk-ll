package com.example.starter.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 幂等指纹工具：对 requestId 与全部业务字段做 SHA-256，
 * 同键同参可重放首次结果，同键异参判冲突。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 计算指纹；字段按声明顺序以竖线拼接，null 以固定占位符参与。
     * 历史入口沿用本方法；字段值可能包含竖线分隔符的新入口请使用 {@link #structured(Object...)}。
     *
     * @param parts requestId 及全部业务字段
     * @return 64 位十六进制摘要
     */
    public static String of(Object... parts) {
        String raw = Stream.of(parts)
                .map(part -> part == null ? "<null>" : part.toString())
                .collect(Collectors.joining("|"));
        return sha256(raw);
    }

    /**
     * 结构化指纹：每段以「字符长度:内容」前缀编码后顺序拼接再做 SHA-256。
     * 编码无歧义：字段值本身包含任意分隔符（如竖线、冒号）时，
     * 单字段 "a|b" 与两字段 "a"、"b" 的编码不同，不会被混淆。
     *
     * @param parts requestId 及全部业务字段（含版本、集合元素与精确时刻）
     * @return 64 位十六进制摘要
     */
    public static String structured(Object... parts) {
        StringBuilder raw = new StringBuilder();
        for (Object part : parts) {
            if (part == null) {
                raw.append("-1:");
            } else {
                String value = part.toString();
                raw.append(value.length()).append(':').append(value);
            }
        }
        return sha256(raw.toString());
    }

    private static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
