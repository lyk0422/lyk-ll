package com.example.starter.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 幂等指纹工具：对 requestId 与全部业务字段做 SHA-256，
 * 同键同参可重放首次结果，同键异参判冲突。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 计算指纹；字段按声明顺序以竖线拼接，null 以固定占位符参与。
     *
     * @param parts requestId 及全部业务字段
     * @return 64 位十六进制摘要
     */
    public static String of(Object... parts) {
        String raw = Stream.of(parts)
                .map(part -> part == null ? "<null>" : part.toString())
                .collect(Collectors.joining("|"));
        return sha256(raw);
    }

    /**
     * 结构化指纹：每个字段以“类型:UTF-8字节长度:值”编码，字段首尾定界，
     * 即使合法文本本身含有分隔符（如冒号、竖线）也无法伪造字段边界，无歧义。
     *
     * @param parts requestId 及全部业务字段（标量），集合请先用 {@link #stringList} 编码
     * @return 64 位十六进制摘要
     */
    public static String structured(Object... parts) {
        StringBuilder raw = new StringBuilder();
        for (Object part : parts) {
            if (part == null) {
                raw.append("N:0:");
            } else {
                byte[] bytes = part.toString().getBytes(StandardCharsets.UTF_8);
                raw.append("S:").append(bytes.length).append(':').append(part);
            }
        }
        return sha256(raw.toString());
    }

    /**
     * 将字符串集合编码为单个结构化字段：先给元素个数，再逐元素长度定界；
     * null 集合与空集合编码不同，元素顺序参与指纹。
     */
    public static String stringList(List<String> values) {
        if (values == null) {
            return "N:0:";
        }
        StringBuilder raw = new StringBuilder("L:").append(values.size()).append(':');
        for (String value : values) {
            byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
            raw.append(bytes.length).append(':').append(value == null ? "" : value);
        }
        return raw.toString();
    }

    private static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
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
}
