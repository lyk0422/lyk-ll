package com.example.starter.rt.support;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.example.starter.rt.domain.OperationType;

/**
 * 幂等指纹工具：指纹覆盖 requestId、操作类型与全部业务字段。
 *
 * <p>采用类型标签 + 长度前缀的结构化编码后计算 SHA-256：
 * 业务字段按 Key 字典序排序，键与值均以「类型标签 + 字符长度 + 内容」写入，
 * 集合与嵌套 Map 递归编码，时刻统一归一化为 UTC Instant 文本。
 * 因此参数顺序不同不影响指纹；任一字段不同（包括合法文本中含 '='、'\n'、',' 等
 * 分隔符的情形）指纹必然不同，编码无歧义。</p>
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    /**
     * 计算指纹。
     *
     * @param operation 操作类型
     * @param requestId 幂等键
     * @param params    全部业务字段（值允许为 null、标量、时刻、List 或 Map）
     * @return 64 位十六进制 SHA-256 摘要
     */
    public static String of(OperationType operation, String requestId, Map<String, ?> params) {
        StringBuilder plain = new StringBuilder();
        appendTagged(plain, "O", operation.name());
        appendTagged(plain, "R", requestId);
        appendValue(plain, params == null ? null : new TreeMap<>(params));
        return sha256Hex(plain.toString());
    }

    /** 递归编码任意受支持类型的值。 */
    private static void appendValue(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("N;");
            case String text -> appendTagged(out, "S", text);
            case Integer number -> appendTagged(out, "I", number.toString());
            case Long number -> appendTagged(out, "J", number.toString());
            case Short number -> appendTagged(out, "H", number.toString());
            case Byte number -> appendTagged(out, "Y", number.toString());
            case BigDecimal number -> appendTagged(out, "D", number.stripTrailingZeros().toPlainString());
            case Boolean flag -> appendTagged(out, "B", flag.toString());
            case OffsetDateTime time ->
                    appendTagged(out, "T", time.toInstant().atOffset(ZoneOffset.UTC).toString());
            case Instant instant -> appendTagged(out, "T", instant.atOffset(ZoneOffset.UTC).toString());
            case LocalDateTime time -> appendTagged(out, "L", time.toString());
            case LocalDate date -> appendTagged(out, "A", date.toString());
            case List<?> list -> {
                out.append('[').append(list.size()).append(';');
                for (Object element : list) {
                    appendValue(out, element);
                }
            }
            case Map<?, ?> map -> {
                TreeMap<String, Object> sorted = new TreeMap<>();
                map.forEach((key, item) -> sorted.put(String.valueOf(key), item));
                out.append('{').append(sorted.size()).append(';');
                sorted.forEach((key, item) -> {
                    appendTagged(out, "K", key);
                    appendValue(out, item);
                });
            }
            default -> throw new IllegalArgumentException(
                    "指纹不支持的业务字段类型：" + value.getClass().getName());
        }
    }

    /** 以「标签 + 字符长度 + 内容」写入一个字段，保证含分隔符的合法文本仍可无歧义区分。 */
    private static void appendTagged(StringBuilder out, String tag, String content) {
        out.append(tag).append(content.length()).append(':').append(content).append(';');
    }

    private static String sha256Hex(String plain) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(plain.getBytes(StandardCharsets.UTF_8));
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
