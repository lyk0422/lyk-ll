package com.example.starter.pv.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 请求指纹工具：以字段顺序固定的竖线分隔文本生成 SHA-256，覆盖 requestId 与全部业务字段。
 * null 统一以空串表示，保证同参同指纹、异参不同指纹。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 计算指纹。
     *
     * @param parts 按固定顺序排列的业务字段（含 requestId 与 requestKind）
     * @return 64 位小写十六进制 SHA-256
     */
    public static String of(List<Object> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            Object p = parts.get(i);
            sb.append(p == null ? "" : p.toString());
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
