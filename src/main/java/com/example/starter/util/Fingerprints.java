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
     *
     * @param parts requestId 及全部业务字段
     * @return 64 位十六进制摘要
     */
    public static String of(Object... parts) {
        String raw = Stream.of(parts)
                .map(part -> part == null ? "<null>" : part.toString())
                .collect(Collectors.joining("|"));
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
