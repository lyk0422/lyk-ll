package com.example.starter.audit.persist;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 指标与上游摘要 JSON 序列化：键排序保证规范化存储与跨进程稳定。
 */
public final class JsonUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private JsonUtil() {
    }

    /** 序列化为按键排序的 JSON（null 安全）。 */
    public static String writeMap(Map<String, ?> map) {
        try {
            return MAPPER.writeValueAsString(map == null ? Map.of() : map);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    /** 反序列化对象映射；失败时返回空映射而非抛错，避免单行脏数据拖垮查询。 */
    public static Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(MAPPER.readValue(json, MAP_TYPE));
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }
}
