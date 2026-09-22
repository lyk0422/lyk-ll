package com.example.starter.route.support;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简 JSON 字符串数组编解码工具，避免为固定结构引入额外依赖。
 * 仅处理字符串数组（路径节点/路段序列），不做通用 JSON 解析。
 */
public final class JsonLists {

    private JsonLists() {
    }

    /**
     * 将字符串列表编码为 JSON 数组字符串。
     */
    public static String writeStringList(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escape(values.get(i))).append('"');
        }
        return sb.append(']').toString();
    }

    /**
     * 将 JSON 字符串数组解析为列表；null/空串返回空列表。
     */
    public static List<String> readStringList(String json) {
        List<String> result = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        int i = 0;
        while (i < json.length()) {
            while (i < json.length() && json.charAt(i) != '"') {
                i++;
            }
            if (i >= json.length()) {
                break;
            }
            i++;
            StringBuilder value = new StringBuilder();
            while (i < json.length() && json.charAt(i) != '"') {
                char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) {
                    char next = json.charAt(i + 1);
                    value.append(switch (next) {
                        case '"' -> '"';
                        case '\\' -> '\\';
                        case '/' -> '/';
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case 'r' -> '\r';
                        default -> next;
                    });
                    i += 2;
                } else {
                    value.append(c);
                    i++;
                }
            }
            result.add(value.toString());
            i++;
        }
        return result;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
