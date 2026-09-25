package com.example.starter.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 幂等指纹工具：对 requestId 与全部业务字段计算 SHA-256，
 * 同键同参命中首次结果，同键异参返回 409。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 计算指纹，字段按传入顺序以不可见分隔符连接，避免拼接歧义。
     */
    public static String of(Object... fields) {
        StringBuilder sb = new StringBuilder();
        for (Object field : fields) {
            sb.append("").append(field == null ? "<null>" : field.toString());
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
