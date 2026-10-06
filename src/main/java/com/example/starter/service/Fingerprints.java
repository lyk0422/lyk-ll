package com.example.starter.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 幂等指纹工具：对全部业务字段做长度前缀拼接后取 SHA-256，
 * 保证同键同参与同键异参可可靠区分。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 计算业务字段指纹。
     *
     * @param fields 参与指纹的全部业务字段，顺序固定
     * @return 64 位十六进制摘要
     */
    public static String of(String... fields) {
        StringBuilder canonical = new StringBuilder();
        for (String field : fields) {
            String value = field == null ? "" : field;
            canonical.append(value.length()).append(':').append(value).append('|');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
