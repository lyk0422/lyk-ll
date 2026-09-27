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
        return sha256(raw);
    }

    /**
     * 结构化指纹：每个字段带类型标签与长度前缀编码（null 为 N;，其余为 V长度:值），
     * 合法文本即使包含分隔符也不会产生歧义；BigDecimal 去尾零后按 plain 字符串参与，
     * 保证 1.0 与 1.00 视为同参。
     *
     * @param parts requestId 及全部业务字段（含版本、集合、精确时刻）
     * @return 64 位十六进制摘要
     */
    public static String structured(Object... parts) {
        String raw = Stream.of(parts)
                .map(Fingerprints::encodePart)
                .collect(Collectors.joining(""));
        return sha256(raw);
    }

    private static String encodePart(Object part) {
        if (part == null) {
            return "N;";
        }
        String value = part instanceof java.math.BigDecimal decimal
                ? decimal.stripTrailingZeros().toPlainString()
                : part.toString();
        return "V" + value.length() + ":" + value;
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
