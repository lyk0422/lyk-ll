package com.example.starter.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 请求指纹：覆盖 requestId 与全部业务字段的 SHA-256，用于同键异参冲突判定。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    public static String sha256(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    /**
     * 结构化编码：每个字段以「长度:值」前缀编码拼接，null 编码为 -1:。
     * 字段边界由长度前缀唯一确定，合法文本含分隔符也不会与相邻字段粘连产生歧义。
     */
    public static String encode(String bizType, java.util.List<String> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append(bizType.length()).append(':').append(bizType);
        for (String field : fields) {
            if (field == null) {
                sb.append("-1:");
            } else {
                sb.append(field.length()).append(':').append(field);
            }
        }
        return sb.toString();
    }

    /** 结构化指纹：先长度前缀编码再 SHA-256，保证不同字段组合不会产生相同指纹串。 */
    public static String structured(String bizType, java.util.List<String> fields) {
        return sha256(encode(bizType, fields));
    }
}
