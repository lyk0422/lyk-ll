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
     * 结构化指纹：每段以「长度:内容;」长度前缀编码，合法文本含分隔符也可无歧义区分；
     * null 以空串参与。
     */
    public static String structured(String... parts) {
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            joined.append(encode(part));
        }
        return sha256(joined.toString());
    }

    /**
     * 长度前缀编码单个字段，供集合元素规范化后拼接使用。
     */
    public static String encode(String part) {
        String value = part == null ? "" : part;
        return value.length() + ":" + value + ";";
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
