package com.example.starter.specimen.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 请求指纹：对操作名与规范化后的参数列表计算 SHA-256，用于识别同键异参。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    public static String of(String operation, List<String> parts) {
        StringBuilder sb = new StringBuilder(operation);
        for (String part : parts) {
            sb.append('|').append(part == null ? "" : part);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
