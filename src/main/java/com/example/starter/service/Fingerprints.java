package com.example.starter.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 结构化幂等指纹：对每个字段做长度前缀编码后再取 SHA-256，
 * 字段值本身含分隔符（如 "|"、":"）时仍无歧义，合法文本不会被拼接混淆。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 由全部业务字段计算结构化指纹；null 字段以 -1 长度标记编码，与空串区分。
     */
    public static String of(String... parts) {
        StringBuilder canonical = new StringBuilder();
        for (String part : parts) {
            if (part == null) {
                canonical.append("-1:");
            } else {
                canonical.append(part.length()).append(':').append(part);
            }
            canonical.append('|');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 精确时刻字段编码：ISO-8601 UTC 文本，null 传递为 null 以区别于任意合法时刻。
     */
    public static String at(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
