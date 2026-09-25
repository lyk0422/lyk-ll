package com.example.starter.berth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 幂等指纹工具：覆盖 requestId 与全部业务字段。
 * 采用「字段名=长度:值」的长度前缀规范化序列化，消除字段拼接歧义
 * （例如 ["ab","c"] 与 ["a","bc"] 拼接结果相同的问题）；
 * null 使用独立标记，与空串区分。最终取 SHA-256 十六进制。
 */
public final class Fingerprints {

    private static final char NULL_MARKER = '∅';

    private Fingerprints() {
    }

    /**
     * 计算申请请求的幂等指纹。
     */
    public static String of(ApplyWindowRequest req) {
        StringBuilder sb = new StringBuilder("v1");
        append(sb, "requestId", req.requestId());
        append(sb, "vesselName", req.vesselName());
        append(sb, "berthId", req.berthId());
        append(sb, "startUtc", req.startUtc() == null ? null : req.startUtc().toString());
        append(sb, "endUtc", req.endUtc() == null ? null : req.endUtc().toString());
        append(sb, "draftMeters",
                req.draftMeters() == null ? null : req.draftMeters().stripTrailingZeros().toPlainString());
        append(sb, "pilotsRequired", Integer.toString(req.pilotsRequired()));
        append(sb, "replacesRequestId", req.replacesRequestId());
        return sha256Hex(sb.toString());
    }

    private static void append(StringBuilder sb, String name, String value) {
        sb.append('|').append(name).append('=');
        if (value == null) {
            sb.append(NULL_MARKER);
        } else {
            sb.append(value.length()).append(':').append(value);
        }
    }

    private static String sha256Hex(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
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
