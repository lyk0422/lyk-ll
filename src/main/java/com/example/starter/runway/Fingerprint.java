package com.example.starter.runway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 结构化请求指纹：对各字段做“长度前缀 + 原始字节”编码后计算 SHA-256。
 * 即使合法文本中包含分隔符（如 "|"、换行、数字前缀）也不会产生歧义碰撞；
 * 同时显式编码字段个数，避免 ["ab","c"] 与 ["a","bc"] 这类拼接歧义。
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    /**
     * 计算结构化指纹。
     *
     * @param scope 幂等作用域，作为首段参与指纹
     * @param parts 业务字段（requestId 与全部参数，按固定顺序）
     * @return SHA-256 十六进制字符串
     */
    public static String of(String scope, String... parts) {
        byte[] scopeBytes = scope.getBytes(StandardCharsets.UTF_8);
        byte[][] partBytes = new byte[parts.length][];
        for (int i = 0; i < parts.length; i++) {
            partBytes[i] = parts[i] == null ? new byte[0] : parts[i].getBytes(StandardCharsets.UTF_8);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(lengthPrefix(scopeBytes));
            digest.update(intBytes(parts.length));
            for (byte[] part : partBytes) {
                digest.update(lengthPrefix(part));
            }
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static byte[] lengthPrefix(byte[] bytes) {
        byte[] prefix = intBytes(bytes.length);
        byte[] result = new byte[prefix.length + bytes.length];
        System.arraycopy(prefix, 0, result, 0, 4);
        System.arraycopy(bytes, 0, result, 4, bytes.length);
        return result;
    }

    private static byte[] intBytes(int value) {
        return new byte[] {
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value
        };
    }
}
