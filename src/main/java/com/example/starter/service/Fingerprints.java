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
        return digest(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 结构化无歧义指纹：每段以 4 字节大端长度前缀编码，随后再编码集合元素个数。
     * 即使合法文本本身含有 "="、空格或分隔序列，也无法跨字段/跨元素伪造对齐。
     *
     * @param parts 按固定顺序给出的标量字段（字段名与值交替或整体串接均可，顺序固定即可）
     */
    public static String structured(String... parts) {
        MessageDigest md = digest();
        for (String p : parts) {
            updateLengthPrefixed(md, p == null ? "null" : p);
        }
        return toHex(md.digest());
    }

    /**
     * 结构化无歧义指纹（含集合字段）：标量段长度前缀编码；集合段先写元素个数再逐元素长度前缀编码。
     */
    public static String structured(List<String> scalarParts, List<List<String>> collections) {
        MessageDigest md = digest();
        for (String p : scalarParts) {
            updateLengthPrefixed(md, p == null ? "null" : p);
        }
        for (List<String> col : collections) {
            byte[] count = new byte[4];
            int n = col == null ? 0 : col.size();
            count[0] = (byte) (n >>> 24);
            count[1] = (byte) (n >>> 16);
            count[2] = (byte) (n >>> 8);
            count[3] = (byte) n;
            md.update(count);
            if (col != null) {
                for (String e : col) {
                    updateLengthPrefixed(md, e == null ? "null" : e);
                }
            }
        }
        return toHex(md.digest());
    }

    private static void updateLengthPrefixed(MessageDigest md, String value) {
        byte[] b = value.getBytes(StandardCharsets.UTF_8);
        md.update(new byte[]{
                (byte) (b.length >>> 24), (byte) (b.length >>> 16),
                (byte) (b.length >>> 8), (byte) b.length});
        md.update(b);
    }

    private static String digest(byte[] payload) {
        return toHex(digest().digest(payload));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String toHex(byte[] digest) {
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
