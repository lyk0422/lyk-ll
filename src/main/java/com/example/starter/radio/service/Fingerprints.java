package com.example.starter.radio.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 幂等指纹：覆盖 requestId 与全部业务字段的 SHA-256。
 * 同键同参（指纹一致）返回首次结果；同键异参（指纹不一致）返回 409。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 计算指纹。字段按给定顺序以 '|' 连接后取 SHA-256，调用方必须传入 requestId 及全部业务字段。
     *
     * @deprecated 为既有流程保留；新增写操作应使用 {@link #structured(Object...)}，
     *         其长度前缀编码可区分含分隔符的合法文本与数值类型，不存在边界歧义。
     */
    @Deprecated
    public static String of(Object... fields) {
        StringBuilder canonical = new StringBuilder();
        for (Object field : fields) {
            canonical.append(String.valueOf(field)).append('|');
        }
        return sha256(canonical.toString());
    }

    /**
     * 结构化无歧义指纹：每个字段编码为 {@code <类型标签><UTF-8字节长度>:<负载>} 后拼接，再取 SHA-256。
     * 长度前缀保证字段边界由长度决定，合法文本即使包含 ':'、'|' 等分隔符也无法伪装成字段边界；
     * 类型标签区分字符串 "1" 与数值 1。调用方必须传入 requestId 及全部业务字段（含版本、集合、精确时刻）。
     */
    public static String structured(Object... fields) {
        StringBuilder canonical = new StringBuilder();
        for (Object field : fields) {
            String tag;
            String payload;
            if (field == null) {
                tag = "z";
                payload = "";
            } else if (field instanceof String s) {
                tag = "s";
                payload = s;
            } else if (field instanceof Boolean b) {
                tag = "b";
                payload = b.toString();
            } else if (field instanceof Integer || field instanceof Long) {
                tag = "i";
                payload = String.valueOf(field);
            } else if (field instanceof Double || field instanceof Float) {
                tag = "d";
                payload = String.valueOf(field);
            } else {
                tag = "s";
                payload = String.valueOf(field);
            }
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            canonical.append(tag).append(bytes.length).append(':').append(payload);
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
