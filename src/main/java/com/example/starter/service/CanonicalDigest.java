package com.example.starter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;

/**
 * 请求体规范化摘要：递归排序 JSON 对象键后做 SHA-256，
 * 使仅字段书写顺序不同的同参请求得到相同摘要（用于 requestId 幂等判定）。
 */
public final class CanonicalDigest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CanonicalDigest() {
    }

    /**
     * 计算对象的规范化 SHA-256 十六进制摘要。
     */
    public static String sha256(Object value) {
        try {
            JsonNode tree = MAPPER.valueToTree(value);
            byte[] canonical = MAPPER.writeValueAsBytes(sortTree(tree));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        } catch (Exception ex) {
            throw new IllegalArgumentException("无法序列化导入请求以计算摘要", ex);
        }
    }

    private static JsonNode sortTree(JsonNode node) {
        if (node.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            node.properties().forEach(entry -> sorted.put(entry.getKey(), sortTree(entry.getValue())));
            ObjectNode objectNode = MAPPER.createObjectNode();
            sorted.forEach(objectNode::set);
            return objectNode;
        }
        if (node.isArray()) {
            var arrayNode = MAPPER.createArrayNode();
            node.forEach(child -> arrayNode.add(sortTree(child)));
            return arrayNode;
        }
        return node;
    }
}
