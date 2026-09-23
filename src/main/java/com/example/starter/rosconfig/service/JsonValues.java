package com.example.starter.rosconfig.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 参数值与 JSON 文本互转：参数仅支持标量（字符串/整数/浮点/布尔/null）。
 */
@Component
public class JsonValues {

    private final ObjectMapper objectMapper;

    public JsonValues(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 将数据库中存储的 JSON 文本解析为标量 Java 值；null/空文本返回 null。 */
    public Object parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(text);
            return fromNode(node);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid stored json value: " + text, e);
        }
    }

    /** 将 JsonNode 标量转为 Java 值；null 节点返回 null。 */
    public Object fromNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isFloatingPointNumber()) {
            return node.asDouble();
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        throw new IllegalArgumentException("only scalar parameter values are supported");
    }

    /** 将标量值序列化为 JSON 文本；null 序列化为字面 null。 */
    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot serialize parameter value", e);
        }
    }
}
