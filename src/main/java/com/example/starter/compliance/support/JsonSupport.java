package com.example.starter.compliance.support;

import com.example.starter.compliance.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;

/**
 * 快照序列化与幂等指纹工具：
 * 快照以 JSON 落库，保证可复算、重复查询结果稳定；
 * 指纹规范化 BigDecimal 标度（1.0 与 1.00 视为同参）并递归按字段名排序。
 */
public final class JsonSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonSupport() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SNAPSHOT_UNREADABLE",
                    "存储快照无法解析");
        }
    }

    public static <T> T convert(Object value, Class<T> type) {
        return MAPPER.convertValue(value, type);
    }

    public static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SNAPSHOT_UNREADABLE",
                    "存储快照无法解析");
        }
    }

    /**
     * 反序列化幂等重放响应：存储体为首次结果（replayed=false），重放时将响应体 replayed 置为 true，
     * 其余字段与首次结果完全一致。
     */
    public static <T> T readReplayed(String json, Class<T> type) {
        JsonNode node = readTree(json);
        if (node.isObject() && node.has("replayed")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("replayed", true);
        }
        return MAPPER.convertValue(node, type);
    }

    /**
     * 规范化业务报文：BigDecimal 去尾随零（1.00 与 1.0 视为同参），对象键递归字典序排序，
     * 输出稳定 JSON 字符串作为 requestId 幂等指纹输入。
     */
    public static String canonical(Object value) {
        Object normalized = normalize(MAPPER.convertValue(value, JsonNode.class));
        try {
            return MAPPER.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Object normalize(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            node.fields().forEachRemaining(entry ->
                    sorted.put(entry.getKey(), normalize(entry.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>(node.size());
            node.forEach(child -> list.add(normalize(child)));
            return list;
        }
        if (node.isNumber()) {
            return new BigDecimal(node.asText()).stripTrailingZeros().toPlainString();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.asText();
    }
}
