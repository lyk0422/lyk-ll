package com.example.starter.slam.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 规范化内容摘要工具：以确定性的字符串表示生成 SHA-256，用于同 ID/同 requestId 改参冲突检测。
 */
public final class ContentHashes {

    private ContentHashes() {
    }

    /**
     * 对若干规范化字段计算 SHA-256 十六进制摘要。
     *
     * @param parts 字段按固定顺序拼接；List 按元素顺序展开
     * @return 64 位十六进制摘要
     */
    public static String sha256(List<Object> parts) {
        String canonical = canonical(parts);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private static String canonical(List<Object> parts) {
        StringBuilder builder = new StringBuilder();
        for (Object part : parts) {
            if (builder.length() > 0) {
                builder.append('|');
            }
            if (part instanceof List<?> list) {
                builder.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        builder.append(',');
                    }
                    builder.append(canonicalScalar(list.get(i)));
                }
                builder.append(']');
            } else {
                builder.append(canonicalScalar(part));
            }
        }
        return builder.toString();
    }

    private static String canonicalScalar(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Double d) {
            // 避免 -0.0 与 0.0 文本差异
            if (d == 0.0) {
                return "0.0";
            }
            return Double.toString(d);
        }
        return value.toString();
    }
}
