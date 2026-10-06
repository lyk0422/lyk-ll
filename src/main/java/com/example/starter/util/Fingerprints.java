package com.example.starter.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 幂等指纹工具：指纹覆盖 requestId 与全部业务字段，字段按固定顺序以 '|' 拼接后取 SHA-256。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 计算指纹。第一个字段必须为 requestId，其余为全部业务字段（null 以空串参与）。
     */
    public static String of(String requestId, Object... businessFields) {
        StringBuilder sb = new StringBuilder(requestId == null ? "" : requestId);
        for (Object field : businessFields) {
            sb.append('|').append(field == null ? "" : field.toString());
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
