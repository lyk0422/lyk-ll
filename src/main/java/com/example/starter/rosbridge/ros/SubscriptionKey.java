package com.example.starter.rosbridge.ros;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 共享订阅去重键：Topic + 消息类型 + QoS 完全相同的订阅复用同一个上游订阅。
 *
 * @param topic       ROS 2 Topic 名称
 * @param messageType 消息类型，如 std_msgs/String
 * @param qosJson     规范化（按 key 排序）后的 QoS JSON 串
 */
public record SubscriptionKey(String topic, String messageType, String qosJson) {

    /**
     * 将客户端提供的 QoS 节点规范化为稳定 JSON 串（key 字典序），保证等价 QoS 复用同一上游。
     * 空/缺省 QoS 统一为空对象串。
     */
    public static String normalizeQos(com.fasterxml.jackson.databind.ObjectMapper mapper, JsonNode qos) {
        if (qos == null || qos.isNull() || qos.isEmpty()) {
            return "{}";
        }
        TreeMap<String, Object> sorted = new TreeMap<>();
        qos.fields().forEachRemaining(e -> sorted.put(e.getKey(), parseNode(e.getValue())));
        try {
            return mapper.writeValueAsString(sorted);
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid qos: " + qos, ex);
        }
    }

    private static Object parseNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            TreeMap<String, Object> map = new TreeMap<>();
            node.fields().forEachRemaining(e -> map.put(e.getKey(), parseNode(e.getValue())));
            return map;
        }
        if (node.isArray()) {
            java.util.List<Object> list = new java.util.ArrayList<>(node.size());
            node.forEach(child -> list.add(parseNode(child)));
            return list;
        }
        if (node.isIntegralNumber()) {
            return node.longValue();
        }
        if (node.isNumber()) {
            return node.doubleValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.asText();
    }

    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("topic", topic);
        map.put("type", messageType);
        map.put("qos", qosJson);
        return map;
    }
}
