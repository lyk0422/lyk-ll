package com.example.starter.slam.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 写入内容判重工具：对请求的业务字段（不含 requestId）生成稳定 SHA-256 摘要。
 * JSON 字段先按键排序再序列化，保证字段顺序不影响判重。
 */
public final class ContentHashes {

    private static final ObjectMapper SORTED_MAPPER = JsonMapper.builder().build();

    private ContentHashes() {
    }

    public static String sha256(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String canonicalJson(JsonNode node) {
        try {
            Object sorted = sort(node);
            return SORTED_MAPPER.writeValueAsString(sorted);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid measurement JSON", e);
        }
    }

    private static Object sort(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> map = new TreeMap<>();
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                map.put(entry.getKey(), sort(entry.getValue()));
            }
            return map;
        }
        if (node.isArray()) {
            java.util.List<Object> list = new java.util.ArrayList<>(node.size());
            node.forEach(child -> list.add(sort(child)));
            return list;
        }
        // 数值/布尔/文本等标量直接保留 Jackson 节点，保证数值表示不被改变
        return node;
    }

    /**
     * 关键帧业务内容摘要。
     */
    public static String keyframeHash(String source, int version, double x, double y, double theta,
                                      JsonNode measurement) {
        Map<String, Object> body = new TreeMap<>();
        body.put("kind", "KEYFRAME");
        body.put("source", source);
        body.put("version", version);
        body.put("x", x);
        body.put("y", y);
        body.put("theta", theta);
        body.put("measurement", sort(measurement));
        return sha256(toJson(body));
    }

    /**
     * 图边业务内容摘要。
     */
    public static String edgeHash(String type, String source, int version, String fromKeyframeId,
                                  String toKeyframeId, double dx, double dy, double dtheta,
                                  List<Double> covariance, Double score, Double residual) {
        Map<String, Object> body = new TreeMap<>();
        body.put("kind", "EDGE");
        body.put("type", type);
        body.put("source", source);
        body.put("version", version);
        body.put("fromKeyframeId", fromKeyframeId);
        body.put("toKeyframeId", toKeyframeId);
        body.put("dx", dx);
        body.put("dy", dy);
        body.put("dtheta", dtheta);
        body.put("covariance", covariance);
        body.put("score", score);
        body.put("residual", residual);
        return sha256(toJson(body));
    }

    private static String toJson(Object value) {
        try {
            return SORTED_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to canonicalize content", e);
        }
    }
}
