package com.example.starter.cutover.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;

/**
 * 幂等指纹工具：将任意请求对象规范化为字段名排序的 JSON，再计算 SHA-256。
 * 指纹覆盖 requestId 与全部业务字段，字段顺序差异不影响指纹。
 */
public final class FingerprintUtil {

    private static final ObjectMapper MAPPER = com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private FingerprintUtil() {
    }

    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 计算业务对象的规范化指纹。
     */
    public static String fingerprint(Object request) {
        JsonNode normalized = sort(MAPPER.valueToTree(request));
        try {
            return sha256(MAPPER.writeValueAsString(normalized));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("请求指纹序列化失败", e);
        }
    }

    /**
     * 递归按字段名排序对象节点，保证序列化结果稳定。
     */
    private static JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            node.properties().forEach(e -> sorted.put(e.getKey(), sort(e.getValue())));
            var object = MAPPER.createObjectNode();
            sorted.forEach(object::set);
            return object;
        }
        if (node.isArray()) {
            var array = MAPPER.createArrayNode();
            node.forEach(child -> array.add(sort(child)));
            return array;
        }
        return node;
    }
}
