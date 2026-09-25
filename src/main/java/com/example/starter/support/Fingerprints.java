package com.example.starter.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 请求指纹工具：对规范化字符串生成 SHA-256（十六进制），用于 requestId 幂等冲突判定。
 * 调用方负责把 requestId 与全部业务字段按稳定顺序拼接为规范化文本。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /** 对规范化文本计算 SHA-256 十六进制指纹。 */
    public static String sha256(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
