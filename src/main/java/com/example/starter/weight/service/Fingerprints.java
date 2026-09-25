package com.example.starter.weight.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 幂等指纹工具：对 requestId 与全部业务字段计算 SHA-256，
 * 用于“同键同参返回首次结果、同键异参返回 409”的判定。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 计算幂等指纹。
     *
     * @param parts 参与指纹的字段（含 requestId 与全部业务字段），按固定顺序传入
     * @return 64 位十六进制 SHA-256
     */
    public static String of(String... parts) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
        for (String part : parts) {
            byte[] bytes = (part == null ? "" : part).getBytes(StandardCharsets.UTF_8);
            digest.update(bytes);
            digest.update((byte) 0x1F);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
