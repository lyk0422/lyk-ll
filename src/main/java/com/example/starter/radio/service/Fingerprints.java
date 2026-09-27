package com.example.starter.radio.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 幂等指纹：覆盖 requestId 与全部业务字段的 SHA-256。
 * 同键同参（指纹一致）返回首次结果；同键异参（指纹不一致）返回 409。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 计算指纹。字段按给定顺序以 '|' 连接后取 SHA-256，调用方必须传入 requestId 及全部业务字段。
     */
    public static String of(Object... fields) {
        StringBuilder canonical = new StringBuilder();
        for (Object field : fields) {
            canonical.append(String.valueOf(field)).append('|');
        }
        return sha256(canonical.toString());
    }

    /**
     * 结构化无歧义指纹：每个字段以“S(长度):值”编码，字段间换行分隔，
     * 因而合法文本即便包含分隔符（如 '|' 或换行）也无法构造出碰撞。
     * fields 顺序由调用方固定，须包含操作类型、requestId、版本、集合与精确时刻等全部参数。
     */
    public static String structured(List<String> fields) {
        StringBuilder canonical = new StringBuilder();
        for (String field : fields) {
            byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
            canonical.append("S(").append(bytes.length).append("):").append(field).append('\n');
        }
        return sha256(canonical.toString());
    }

    private static String sha256(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
