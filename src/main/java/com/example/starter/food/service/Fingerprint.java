package com.example.starter.food.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * 幂等指纹：对 requestId 与全部业务字段做规范化 JSON 后取 SHA-256。
 * 对象键按字典序排列，列表保持提交顺序；数值按十进制规范化（0.50 与 0.5 视为同参）。
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    public static String sha256(ObjectMapper mapper, Object value) {
        try {
            Object canonical = canonicalize(mapper.valueToTree(value));
            byte[] json = mapper.writeValueAsBytes(canonical);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(json);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("无法序列化指纹输入", e);
        }
    }

    private static Object canonicalize(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            node.fields().forEachRemaining(e -> sorted.put(e.getKey(), canonicalize(e.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>(node.size());
            for (JsonNode child : node) {
                list.add(canonicalize(child));
            }
            return list;
        }
        if (node.isNumber()) {
            // 统一按十进制标量规范化：0.50 与 0.5 同值，整型与浮点各有稳定表示
            return node.decimalValue().stripTrailingZeros().toPlainString();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.asText();
    }
}
