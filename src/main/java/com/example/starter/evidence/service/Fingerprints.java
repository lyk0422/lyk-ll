package com.example.starter.evidence.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 请求键指纹工具：对请求关键参数计算 SHA-256，用于幂等键的同键异参检测。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 计算规范化参数的 SHA-256 指纹（小写十六进制）。
     *
     * @param canonical 以分隔符连接的规范化参数串
     */
    public static String sha256(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", ex);
        }
    }
}
