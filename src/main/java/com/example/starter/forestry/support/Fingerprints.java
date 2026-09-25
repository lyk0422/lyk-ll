package com.example.starter.forestry.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 幂等指纹工具：以 requestId 与全部业务字段拼接的规范串计算 SHA-256。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 计算幂等指纹。字段按声明顺序以竖线连接，null 以空串占位。
     *
     * @param fields 第一个元素必须为 requestId，其后为全部业务字段
     * @return 64 位十六进制摘要
     */
    public static String of(Object... fields) {
        StringBuilder sb = new StringBuilder();
        for (Object field : fields) {
            sb.append(field == null ? "" : field.toString()).append('|');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
