package com.example.starter.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 幂等指纹工具：对 requestId 与全部业务字段做规范化拼接后取 SHA-256。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 计算请求指纹。第一个参数约定为 requestId，其余为全部业务字段；
     * null 以占位串参与计算，字段顺序由调用方固定。
     */
    public static String of(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object part : parts) {
            sb.append('\n').append(part == null ? "<null>" : part.toString());
        }
        return sha256(sb.toString());
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
