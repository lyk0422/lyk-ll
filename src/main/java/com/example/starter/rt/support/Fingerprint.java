package com.example.starter.rt.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.example.starter.rt.domain.OperationType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等指纹工具：指纹覆盖 requestId、操作类型与全部业务字段。
 * 业务字段按 Key 字典序排序后参与 SHA-256，保证参数顺序不同不影响指纹、任一字段不同指纹必然不同。
 */
public final class Fingerprint {

    private static final ObjectMapper JSON = new ObjectMapper();

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
     * 结构化指纹：参数先规范化为“键字典序排序的 JSON 文档”再取 SHA-256。
     * 字符串经 JSON 转义、集合保持顺序嵌套，合法文本即使含有分隔符（如 =、换行、引号）
     * 也不会与其他参数组合产生相同明文，保证无歧义。
     *
     * @param operation 操作类型
     * @param requestId 幂等键
     * @param params    全部业务字段，值限 String/Number/Boolean/null 及它们的 Map/List 嵌套
     * @return 64 位十六进制 SHA-256 摘要
     */
    public static String structured(OperationType operation, String requestId, Map<String, ?> params) {
        Object normalized = normalize(params);
        String json;
        try {
            json = JSON.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("指纹参数序列化失败", e);
        }
        return sha256(operation.name() + '\n' + requestId + '\n' + json);
    }

    /** 递归规范化：Map 按键字典序排序，List 保持元素顺序，标量原样保留。 */
    private static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), normalize(item)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> normalized = new ArrayList<>(list.size());
            for (Object item : list) {
                normalized.add(normalize(item));
            }
            return normalized;
        }
        return value;
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
