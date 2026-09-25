package com.example.starter.slaughter.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 幂等指纹计算：SHA-256，覆盖 requestId、操作类型与全部业务字段。
 * 所有层级的 Map 均按键名字典序规范化，避免字段顺序差异导致误判。
 */
@Component
public class FingerprintCalculator {

    private final ObjectMapper canonicalMapper = new ObjectMapper();

    /**
     * @param operation 操作类型
     * @param requestId 幂等键（纳入指纹）
     * @param fields    全部业务字段（任意层级 Map 均按键名字典序排序）
     * @return 64 位十六进制 SHA-256 摘要
     */
    public String calculate(String operation, String requestId, Map<String, ?> fields) {
        TreeMap<String, Object> canonical = new TreeMap<>();
        canonical.put("operation", operation);
        canonical.put("requestId", requestId);
        canonical.putAll(fields);
        try {
            String json = canonicalMapper.writeValueAsString(canonicalize(canonical));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("failed to calculate idempotent fingerprint", ex);
        }
    }

    /** 递归将 Map 规范化为 TreeMap，List 保持顺序，保证序列化稳定。 */
    @SuppressWarnings("unchecked")
    private static Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), canonicalize(v)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(FingerprintCalculator::canonicalize).toList();
        }
        return value;
    }
}
