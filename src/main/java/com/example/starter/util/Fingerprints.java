package com.example.starter.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 幂等指纹工具：对 requestId 与全部业务字段做 SHA-256，
 * 同键同参可重放首次结果，同键异参判冲突。
 *
 * <p>编码采用结构化长度前缀，字段边界无歧义：合法文本即使含有分隔符，
 * 或字段拆分位置不同（如 "ab","c" 与 "a","bc"），指纹也不同；
 * 集合按元素逐个编码并携带元素数，与等长展开参数列表区分。</p>
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 计算指纹；字段按声明顺序编码，null 以固定标记参与，集合逐元素编码。
     *
     * @param parts requestId 及全部业务字段（含版本、集合与精确时刻）
     * @return 64 位十六进制摘要
     */
    public static String of(Object... parts) {
        StringBuilder raw = new StringBuilder();
        for (Object part : parts) {
            encode(part, raw);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 长度前缀编码：标量写作 {@code S<长度>:<值>}，null 写作 {@code N:}，
     * 集合写作 {@code L<元素数>:<逐元素编码>}。编码结果自界定，拼接无歧义。
     */
    private static void encode(Object part, StringBuilder out) {
        if (part == null) {
            out.append("N:");
            return;
        }
        if (part instanceof Iterable<?> iterable) {
            StringBuilder elements = new StringBuilder();
            int size = 0;
            for (Object element : iterable) {
                encode(element, elements);
                size++;
            }
            out.append('L').append(size).append(':').append(elements);
            return;
        }
        String value = part.toString();
        out.append('S').append(value.length()).append(':').append(value);
    }
}
