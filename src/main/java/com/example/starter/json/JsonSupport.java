package com.example.starter.json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Jackson JSON 简单封装：资料目录数组与幂等响应体的序列化/反序列化。
 */
@Component
public class JsonSupport {

    private final ObjectMapper objectMapper;

    public JsonSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 序列化为字符串，失败抛出 IllegalStateException（合成数据均为可序列化类型）。 */
    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("JSON 序列化失败", ex);
        }
    }

    /** 将 JSON 数组文本反序列化为字符串列表，空文本返回空列表。 */
    public List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception ex) {
            throw new IllegalStateException("JSON 反序列化失败: " + json, ex);
        }
    }

    /** 将任意响应体 JSON 反序列化为通用对象（Map/List/字面量）。 */
    public Object readObject(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception ex) {
            throw new IllegalStateException("JSON 反序列化失败", ex);
        }
    }

    /** 将已解析的响应体对象（如重放得到的 Map）转换为指定视图类型。 */
    public <T> T convertValue(Object value, Class<T> type) {
        if (value == null) {
            return null;
        }
        return objectMapper.convertValue(value, type);
    }
}
