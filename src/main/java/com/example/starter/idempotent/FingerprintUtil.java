package com.example.starter.idempotent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * 幂等指纹：SHA-256 覆盖 requestId 与全部业务字段（含版本、集合与精确时刻）。
 *
 * <p>结构化无歧义：requestId 以「长度:原文」长度前缀编码后再拼接规范化 JSON，
 * 合法文本中即使含分隔符 '|'、':' 也不会与前后字段粘连产生歧义；
 * 数值按规范值比较：所有小数先 stripTrailingZeros 归一，整数与小数文本等价
 * （如 10 与 10.0）得到相同指纹；对象字段按名排序，集合保持顺序。
 */
@Component
public class FingerprintUtil {

    private final ObjectMapper objectMapper;

    public FingerprintUtil(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String fingerprint(String requestId, Object payload) {
        try {
            JsonNode canonical = canonicalize(objectMapper.valueToTree(payload));
            String canonicalJson = objectMapper.writeValueAsString(canonical);
            // 长度前缀编码：requestId 含分隔符也能唯一切分
            String material = requestId.length() + ":" + requestId + "|" + canonicalJson;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("fingerprint computation failed", e);
        }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            // 字段按名排序，消除构造顺序差异
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                sorted.put(entry.getKey(), canonicalize(entry.getValue()));
            }
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            sorted.forEach(result::set);
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            for (JsonNode element : node) {
                result.add(canonicalize(element));
            }
            return result;
        }
        if (node.isNumber()) {
            // 规范值比较：10、10.0、10.000 等价
            return DecimalNode.valueOf(node.decimalValue().stripTrailingZeros());
        }
        return node;
    }
}
