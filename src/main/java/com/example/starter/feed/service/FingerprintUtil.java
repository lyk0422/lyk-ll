package com.example.starter.feed.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * requestId 幂等指纹：递归按键排序规范化 JSON 后取 SHA-256。
 * 指纹覆盖 requestId 与全部业务字段；同键异参必然得到不同指纹，
 * 列表顺序（业务字段顺序）保留。
 */
public final class FingerprintUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private FingerprintUtil() {
    }

    /**
     * 计算任意可序列化业务对象的稳定指纹。
     */
    public static String sha256(Object payload) {
        try {
            Object raw = MAPPER.convertValue(payload, Object.class);
            Object canonical = normalize(raw);
            String json = MAPPER.writeValueAsString(canonical);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("指纹计算失败", e);
        }
    }

    /**
     * 将所有对象节点替换为 TreeMap 以保证键顺序稳定；列表顺序原样保留。
     */
    @SuppressWarnings("unchecked")
    private static Object normalize(Object node) {
        if (node instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), normalize(v)));
            return sorted;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(normalize(item));
            }
            return out;
        }
        return node;
    }
}
