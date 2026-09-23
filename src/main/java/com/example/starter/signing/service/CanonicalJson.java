package com.example.starter.signing.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 确定性规范 JSON 序列化：对象键按字典序（UTF-8 字节序由 {@link TreeMap} 的 String 自然序近似，
 * 键均为 ASCII）、无多余空白、字符串严格转义。仅用于事件哈希与证据包固化，
 * 保证同一内容永远产出完全相同的字节。
 */
public final class CanonicalJson {

    private CanonicalJson() {
    }

    /**
     * 按字典序序列化 Map。
     */
    public static String object(Map<String, ?> map) {
        StringBuilder sb = new StringBuilder();
        writeObject(sb, new TreeMap<>(map));
        return sb.toString();
    }

    /**
     * 保持插入顺序的对象（用于已经排好序的 {@link LinkedHashMap}）。
     */
    public static String orderedObject(LinkedHashMap<String, ?> map) {
        StringBuilder sb = new StringBuilder();
        writeObject(sb, map);
        return sb.toString();
    }

    /**
     * 序列化列表。
     */
    public static String array(List<?> list) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, list);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b.booleanValue());
        } else if (value instanceof Number n) {
            sb.append(n.toString());
        } else if (value instanceof Instant instant) {
            writeString(sb, instant.toString());
        } else if (value instanceof Enum<?> e) {
            writeString(sb, e.name());
        } else if (value instanceof Map<?, ?> map) {
            writeObject(sb, (Map<String, ?>) map);
        } else if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeValue(sb, list.get(i));
            }
            sb.append(']');
        } else {
            writeString(sb, value.toString());
        }
    }

    private static void writeObject(StringBuilder sb, Map<String, ?> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, ?> entry : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, entry.getKey());
            sb.append(':');
            writeValue(sb, entry.getValue());
        }
        sb.append('}');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
