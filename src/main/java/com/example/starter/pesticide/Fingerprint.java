package com.example.starter.pesticide;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 幂等指纹工具：对 requestId 与全部业务字段计算 SHA-256。
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    /**
     * 计算指纹；字段按传入顺序拼接，null 以空串参与。
     */
    public static String of(String... parts) {
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            joined.append(part == null ? "" : part).append('|');
        }
        return sha256(joined.toString());
    }

    /**
     * 计算结构化指纹：每段以「长度:内容」长度前缀编码，字段无歧义，
     * 合法文本含分隔符（如 |、:）也能与字段边界区分。
     */
    public static String structured(String... parts) {
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            String value = part == null ? "" : part;
            joined.append(value.length()).append(':').append(value);
        }
        return sha256(joined.toString());
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
