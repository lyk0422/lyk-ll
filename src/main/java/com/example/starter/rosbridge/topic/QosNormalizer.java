package com.example.starter.rosbridge.topic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 将客户端提供的 QoS JSON 规范化：对象键按字典序排序、数组保持顺序、
 * 紧凑序列化，使语义相同但字段顺序不同的 QoS 复用同一上游订阅。
 */
public final class QosNormalizer {

    private final ObjectMapper mapper;

    public QosNormalizer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 返回规范化结果：canonicalJson 为键有序的紧凑 JSON，hash 为其 SHA-256 摘要，
     * valueMap 为传给 ROS 适配器的有序 Map。空 QoS 规范化为 {@code {}}。
     */
    public NormalizedQos normalize(JsonNode qos) {
        Object sorted = toOrdered(qos == null || qos.isNull() ? mapper.createObjectNode() : qos);
        String json;
        try {
            json = mapper.writeValueAsString(sorted);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法序列化 QoS: " + qos, e);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> valueMap = (Map<String, Object>) sorted;
        return new NormalizedQos(json, sha256Hex(json), valueMap);
    }

    /** 规范化 QoS 结果。 */
    public record NormalizedQos(String canonicalJson, String hash, Map<String, Object> valueMap) {
    }

    /** 将 JsonNode 转为键有序的普通 Java 结构。 */
    private static Object toOrdered(JsonNode node) {
        JsonNodeType type = node.getNodeType();
        return switch (type) {
            case OBJECT -> {
                Map<String, Object> map = new TreeMap<>();
                node.fields().forEachRemaining(e -> map.put(e.getKey(), toOrdered(e.getValue())));
                yield map;
            }
            case ARRAY -> {
                List<Object> list = new ArrayList<>(node.size());
                node.forEach(child -> list.add(toOrdered(child)));
                yield list;
            }
            case BOOLEAN -> node.booleanValue();
            case NUMBER -> node.numberValue();
            case NULL, MISSING -> null;
            default -> node.asText();
        };
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hashBytes.length * 2);
            for (byte b : hashBytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }
}
