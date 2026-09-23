package com.example.starter.signing.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 工具：统一输出小写十六进制，作为文档摘要、事件链与指纹计算的唯一入口。
 */
public final class Hashes {

    /** 事件链创世前哈希：64 个 0。 */
    public static final String GENESIS_PREVIOUS = "0".repeat(64);

    private Hashes() {
    }

    /**
     * 计算 UTF-8 字节的 SHA-256 摘要。
     *
     * @param input 原文
     * @return 小写十六进制摘要
     */
    public static String sha256(String input) {
        return sha256(input.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 计算字节数组的 SHA-256 摘要。
     *
     * @param bytes 原始字节
     * @return 小写十六进制摘要
     */
    public static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * 归一化外部传入的十六进制摘要（小写），便于比较。
     *
     * @param hash 十六进制摘要
     * @return 小写形式
     */
    public static String normalize(String hash) {
        return hash == null ? null : hash.toLowerCase();
    }
}
