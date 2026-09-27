package com.example.starter.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 幂等指纹工具：覆盖 requestId 与全部业务字段的规范化串行化后取 SHA-256。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 以 "key=value" 片段拼接规范化文本并计算 SHA-256 十六进制摘要。
     * 调用方须按固定顺序传入全部业务字段，null 以 "null" 参与。
     */
    public static String of(String... keyValues) {
        StringBuilder sb = new StringBuilder();
        for (String kv : keyValues) {
            sb.append(kv == null ? "null" : kv).append(' ');
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 结构化、无歧义的指纹：每个字段独立成段，段首 4 字节长度前缀（大端，UTF-8 字节数），
     * 使含分隔符（'='、空格、换行等）的合法文本也无法与其他取值/字段划分混淆。
     *
     * @param keyValues 按固定顺序排列的 "字段名=值" 片段；值允许为任意合法文本（含分隔符）
     */
    public static String structured(String... keyValues) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (String kv : keyValues) {
            writeLengthPrefixed(out, kv == null ? "null" : kv);
            out.write('|');
        }
        return sha256(out.toByteArray());
    }

    /**
     * 集合字段的结构化编码：先元素个数，再逐元素长度前缀，避免拼接分隔符歧义。
     * 调用方须先去重并按固定顺序排序。
     */
    public static String list(List<String> values) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] n = ("n=" + values.size() + ";").getBytes(StandardCharsets.UTF_8);
        out.write(n, 0, n.length);
        for (String v : values) {
            writeLengthPrefixed(out, v == null ? "null" : v);
            out.write(';');
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void writeLengthPrefixed(java.io.ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.write((bytes.length >>> 24) & 0xFF);
        out.write((bytes.length >>> 16) & 0xFF);
        out.write((bytes.length >>> 8) & 0xFF);
        out.write(bytes.length & 0xFF);
        out.write(bytes, 0, bytes.length);
    }

    private static String sha256(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
