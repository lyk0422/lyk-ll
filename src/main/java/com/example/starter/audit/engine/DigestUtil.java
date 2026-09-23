package com.example.starter.audit.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 摘要工具：统一十六进制输出（小写 64 位）。
 */
public final class DigestUtil {

    private DigestUtil() {
    }

    /** 计算字符串（UTF-8）的 SHA-256。 */
    public static String sha256(String content) {
        return sha256(content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8));
    }

    /** 计算字节数组的 SHA-256。 */
    public static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content == null ? new byte[0] : content);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }
}
