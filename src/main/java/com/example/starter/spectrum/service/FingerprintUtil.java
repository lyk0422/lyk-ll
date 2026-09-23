package com.example.starter.spectrum.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 请求指纹工具：对规范化请求参数计算 SHA-256。
 */
public final class FingerprintUtil {

    private FingerprintUtil() {
    }

    /**
     * 计算参数指纹。各片段以 NUL 字符分隔，避免字段内容拼接歧义。
     *
     * @param parts 已规范化、顺序固定的参数片段
     * @return 64 位小写十六进制 SHA-256
     */
    public static String sha256(String... parts) {
        String joined = String.join("\u0000", parts);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(joined.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
