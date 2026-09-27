package com.example.starter.rt.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

import com.example.starter.rt.domain.OperationType;

/**
 * 幂等指纹工具：指纹覆盖 requestId、操作类型与全部业务字段。
 * 业务字段按 Key 字典序排序后参与 SHA-256，保证参数顺序不同不影响指纹、任一字段不同指纹必然不同。
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    /**
     * 计算指纹。
     *
     * @param operation 操作类型
     * @param requestId 幂等键
     * @param params    全部业务字段（值允许为 null，按字面量 "null" 参与）
     * @return 64 位十六进制 SHA-256 摘要
     */
    public static String of(OperationType operation, String requestId, Map<String, ?> params) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        if (params != null) {
            sorted.putAll(params);
        }
        StringBuilder plain = new StringBuilder();
        plain.append(operation.name()).append('\n').append(requestId).append('\n');
        sorted.forEach((key, value) -> plain.append(key).append('=')
                .append(value == null ? "null" : value.toString()).append('\n'));
        return sha256(plain.toString());
    }

    /**
     * 结构化指纹：参数结构（Map/List/字符串/数值/布尔/null）先编码为规范 JSON
     * （对象键按字典序、字符串 JSON 转义），再参与 SHA-256。
     * 字符串经转义后无分隔符歧义：合法文本中即使含有 '='、换行、引号等字符，
     * 也能与结构边界明确区分，不同参数必然得到不同指纹。
     *
     * @param operation 操作类型
     * @param requestId 幂等键
     * @param structure 参数结构（仅允许 Map/String/Number/Boolean/Collection/null 组合）
     * @return 64 位十六进制 SHA-256 摘要
     */
    public static String structured(OperationType operation, String requestId, Object structure) {
        StringBuilder json = new StringBuilder();
        encodeJson(structure, json);
        return sha256(operation.name() + "\n" + requestId + "\n" + json);
    }

    private static void encodeJson(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), v));
            out.append('{');
            boolean[] first = {true};
            sorted.forEach((k, v) -> {
                if (!first[0]) {
                    out.append(',');
                }
                first[0] = false;
                encodeString(k, out);
                out.append(':');
                encodeJson(v, out);
            });
            out.append('}');
        } else if (value instanceof Collection<?> collection) {
            out.append('[');
            boolean first = true;
            for (Object element : collection) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                encodeJson(element, out);
            }
            out.append(']');
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else {
            encodeString(String.valueOf(value), out);
        }
    }

    /** JSON 字符串转义：引号、反斜杠与控制字符全部转义，保证结构边界无歧义。 */
    private static void encodeString(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static String sha256(String plain) {
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
