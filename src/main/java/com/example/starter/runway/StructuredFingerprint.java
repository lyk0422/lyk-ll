package com.example.starter.runway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 结构化幂等指纹：对各字段做长度前缀编码，并显式编码集合规模与元素结构，
 * 使合法文本包含分隔符（如 "|"、","）时仍无歧义。
 *
 * <p>编码规则：标量 = UTF-8 字节长度 + ":" + 文本；集合 = 元素个数 + "#" + 各元素顺序拼接。
 * 例如 ["a|b","c"] 与 ["a","b|c"] 编码不同。最终对完整编码取 SHA-256 十六进制。</p>
 */
public final class StructuredFingerprint {

    private StructuredFingerprint() {
    }

    /** 计算标量序列的结构化指纹。 */
    public static String sha256(String scope, String... scalarFields) {
        StringBuilder builder = new StringBuilder();
        appendField(builder, scope);
        for (String field : scalarFields) {
            appendField(builder, field == null ? "" : field);
        }
        return digest(builder.toString());
    }

    /**
     * 计算“标量头 + 单个集合（集合元素为多个标量字段）”的结构化指纹。
     *
     * @param scope        幂等作用域
     * @param scalarFields 标量头字段（顺序固定）
     * @param collection   集合；每个元素为一组顺序固定的标量字段
     */
    public static String sha256WithCollection(String scope, List<String> scalarFields,
                                              List<List<String>> collection) {
        StringBuilder builder = new StringBuilder();
        appendField(builder, scope);
        for (String field : scalarFields) {
            appendField(builder, field == null ? "" : field);
        }
        builder.append(collection.size()).append('#');
        for (List<String> element : collection) {
            for (String field : element) {
                appendField(builder, field == null ? "" : field);
            }
        }
        return digest(builder.toString());
    }

    private static void appendField(StringBuilder builder, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        builder.append(bytes.length).append(':').append(value);
    }

    private static String digest(String encoded) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] hash = messageDigest.digest(encoded.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
