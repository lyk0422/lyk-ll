package com.example.starter.pump.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 幂等指纹工具。
 *
 * <p>{@link #sha256} 为历史入口：以 {@code |} 拼接，供已上线接口保持证据一致。
 * 新增潮汐/强排接口使用 {@link #structured}：逐字段“类型标签+字节长度+UTF-8字节”编码，
 * 结构化无歧义——即使合法文本本身含分隔符 {@code |}、冒号或数字，也无法构造跨字段碰撞；
 * 集合以“元素个数+逐元素”编码，调用方须对无序集合先排序以保证同参同指纹。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 计算指纹（历史拼接方式）。入参顺序固定，null 以空串参与摘要，保证同参同指纹。
     */
    public static String sha256(Object... parts) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                joined.append('|');
            }
            joined.append(parts[i] == null ? "" : parts[i].toString());
        }
        return digest(joined.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 结构化指纹：每个标量字段编码为 {@code 类型标签 + ':' + UTF8字节长度 + ':' + 字节}，
     * 列表字段编码为 {@code '[' + 元素个数 + ':' + 各元素结构化编码}。
     * 字段边界由长度决定而非分隔符，故文本含任意合法字符均无歧义。
     *
     * @param parts 标量（String/Number/Boolean/时间类型/null）或已排序的 {@code List<?>}
     */
    public static String structured(Object... parts) {
        StringBuilder canonical = new StringBuilder();
        for (Object part : parts) {
            encode(canonical, part);
        }
        return digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void encode(StringBuilder out, Object value) {
        if (value == null) {
            tagged(out, "n", "");
        } else if (value instanceof List<?> list) {
            out.append("[i:").append(list.size()).append(':');
            for (Object item : list) {
                encode(out, item);
            }
        } else if (value instanceof Boolean b) {
            tagged(out, "b", b.toString());
        } else if (value instanceof Number) {
            // BigDecimal 先去除尾随零，避免 1.0 与 1.00 的标度差异产生不同规范串
            String canonical = value instanceof java.math.BigDecimal bd
                    ? bd.stripTrailingZeros().toPlainString() : value.toString();
            tagged(out, "d", canonical);
        } else if (value instanceof java.time.temporal.TemporalAccessor) {
            tagged(out, "t", value.toString());
        } else {
            tagged(out, "s", value.toString());
        }
    }

    /** 标量字段编码：{@code tag ':' byteLength ':' text}。 */
    private static void tagged(StringBuilder out, String tag, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.append(tag).append(':').append(bytes.length).append(':').append(text);
    }

    private static String digest(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
