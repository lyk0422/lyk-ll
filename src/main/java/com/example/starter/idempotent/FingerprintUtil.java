package com.example.starter.idempotent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * 幂等指纹：SHA-256 覆盖 requestId 与全部业务字段（含版本、集合与精确时刻）。
 *
 * <p>结构化无歧义：requestId 与规范化后的报文以 JSON 数组复合，
 * 文本值由 JSON 转义消歧，合法文本即使含分隔符也不会与结构边界混淆；
 * 对象字段按名排序、数组保序、数值按规范值（去尾零）归一，
 * 整数与小数文本数值等价（如 1、1.0、1.00）得到同一指纹。
 */
@Component
public class FingerprintUtil {

    private final ObjectMapper objectMapper;

    public FingerprintUtil(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String fingerprint(String requestId, Object payload) {
        try {
            JsonNode normalizedPayload = normalize(objectMapper.valueToTree(payload));
            ArrayNode root = objectMapper.createArrayNode();
            root.add(requestId);
            root.add(normalizedPayload);
            String canonical = objectMapper.writeValueAsString(root);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("fingerprint computation failed", e);
        }
    }

    /**
     * 规范化 JSON 树：对象字段排序、数组保序、数值去尾零归一，文本原样保留。
     */
    private JsonNode normalize(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isNumber()) {
            // 数值等价按规范值比较：1 / 1.0 / 1.00 归一为同一数值
            return new DecimalNode(node.decimalValue().stripTrailingZeros());
        }
        if (node.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            for (String name : names) {
                sorted.set(name, normalize(node.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            node.forEach(child -> array.add(normalize(child)));
            return array;
        }
        return node;
    }
}
