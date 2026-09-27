package com.example.starter.pesticide;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 幂等指纹工具：对 requestId 与全部业务字段计算 SHA-256。
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    /**
     * 计算指纹；字段按传入顺序拼接，null 以空串参与。
     * 注意：以 '|' 分隔，仅用于旧入口（字段不含分隔符）；新写操作请使用 structured。
     */
    public static String of(String... parts) {
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            joined.append(part == null ? "" : part).append('|');
        }
        return sha256(joined.toString());
    }

    /**
     * 结构化指纹：先写元素个数，再对每个元素做长度前缀编码（个数#长度#内容），
     * 合法文本即使包含分隔符也不会产生歧义；null 以空串参与。
     */
    public static String structured(List<String> parts) {
        StringBuilder joined = new StringBuilder();
        joined.append(parts.size()).append('#');
        for (String part : parts) {
            String value = part == null ? "" : part;
            joined.append(value.length()).append('#').append(value);
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
